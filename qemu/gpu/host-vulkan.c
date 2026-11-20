/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "host-renderer.h"
#include <math.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>
#include "vulkan-shaders.inc"

#define RGBA VK_FORMAT_R8G8B8A8_UNORM
#define USAGE (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)

struct Renderer {
    VkInstance instance;
    VkPhysicalDevice physical;
    VkPhysicalDeviceMemoryProperties memory;
    VkDevice device;
    VkQueue queue;
    VkImage image;
    VkDeviceMemory image_mem, read_mem;
    VkImageView view;
    VkBuffer read_buffer;
    VkRenderPass pass;
    VkFramebuffer frame;
    VkPipelineLayout layout;
    VkPipeline pipeline;
    VkCommandPool pool;
    VkCommandBuffer cmd;
    VkFence fence;
    void *mapped;
    uint32_t family, width, height;
    size_t bytes;
    bool coherent, readable, broken;
    char name[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE + 64];
};

/* Match EGL's initial single-owner contract. Call only from the owner thread. */
static struct Renderer *live;

static void destroy(struct Renderer *r)
{
    if (!r) return;
    if (r->device) {
        if (r->broken) vkDeviceWaitIdle(r->device);
        if (r->mapped) vkUnmapMemory(r->device, r->read_mem);
        vkDestroyFence(r->device, r->fence, NULL);
        vkDestroyCommandPool(r->device, r->pool, NULL);
        vkDestroyPipeline(r->device, r->pipeline, NULL);
        vkDestroyPipelineLayout(r->device, r->layout, NULL);
        vkDestroyFramebuffer(r->device, r->frame, NULL);
        vkDestroyRenderPass(r->device, r->pass, NULL);
        vkDestroyImageView(r->device, r->view, NULL);
        vkDestroyImage(r->device, r->image, NULL);
        vkDestroyBuffer(r->device, r->read_buffer, NULL);
        vkFreeMemory(r->device, r->image_mem, NULL);
        vkFreeMemory(r->device, r->read_mem, NULL);
        vkDestroyDevice(r->device, NULL);
    }
    if (r->instance) vkDestroyInstance(r->instance, NULL);
    free(r);
}

static VkResult choose_device(struct Renderer *r)
{
    uint32_t n = 0;
    VkResult rc = vkEnumeratePhysicalDevices(r->instance, &n, NULL);
    if (rc != VK_SUCCESS) return rc;
    if (!n) return VK_ERROR_INITIALIZATION_FAILED;
    VkPhysicalDevice *devices = calloc(n, sizeof(*devices));
    if (!devices) return VK_ERROR_OUT_OF_HOST_MEMORY;
    rc = vkEnumeratePhysicalDevices(r->instance, &n, devices);
    int best = -1;
    if (rc == VK_SUCCESS) for (uint32_t i = 0; i < n; ++i) {
        VkPhysicalDeviceProperties p;
        VkImageFormatProperties image;
        vkGetPhysicalDeviceProperties(devices[i], &p);
        /* Prefer Qualcomm hardware, then other GPU hardware, then CPU ICDs. */
        int rank = p.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU ? 0 :
                   p.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ||
                   p.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? 2 : 1;
        if (rank > 0 && p.vendorID == 0x5143) rank = 3;
        if (rank <= best || vkGetPhysicalDeviceImageFormatProperties(devices[i], RGBA,
            VK_IMAGE_TYPE_2D, VK_IMAGE_TILING_OPTIMAL, USAGE, 0, &image) != VK_SUCCESS ||
            image.maxExtent.width < r->width || image.maxExtent.height < r->height ||
            !(image.sampleCounts & VK_SAMPLE_COUNT_1_BIT)) continue;
        uint32_t nf = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &nf, NULL);
        if (!nf) continue;
        VkQueueFamilyProperties *families = calloc(nf, sizeof(*families));
        if (!families) { rc = VK_ERROR_OUT_OF_HOST_MEMORY; break; }
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &nf, families);
        for (uint32_t q = 0; q < nf; ++q) {
            if (!families[q].queueCount || !(families[q].queueFlags & VK_QUEUE_GRAPHICS_BIT)) continue;
            r->physical = devices[i];
            r->family = q;
            best = rank;
            snprintf(r->name, sizeof r->name, "%s [vendor=0x%04x device=0x%04x type=%u]",
                     p.deviceName, p.vendorID, p.deviceID, (unsigned)p.deviceType);
            break;
        }
        free(families);
    }
    free(devices);
    if (rc != VK_SUCCESS) return rc;
    if (!r->physical) return VK_ERROR_FEATURE_NOT_PRESENT;
    vkGetPhysicalDeviceMemoryProperties(r->physical, &r->memory);
    return VK_SUCCESS;
}

static VkResult allocate(struct Renderer *r, VkMemoryRequirements req,
                         VkMemoryPropertyFlags required, VkMemoryPropertyFlags preferred,
                         VkDeviceMemory *out, bool *coherent)
{
    int type = -1;
    for (uint32_t i = 0; i < r->memory.memoryTypeCount; ++i) {
        const VkMemoryPropertyFlags flags = r->memory.memoryTypes[i].propertyFlags;
        if (!(req.memoryTypeBits & (1u << i)) || (flags & required) != required) continue;
        if (type < 0) type = (int)i;
        if ((flags & preferred) == preferred) { type = (int)i; break; }
    }
    if (type < 0) return VK_ERROR_FEATURE_NOT_PRESENT;
    if (coherent) *coherent = (r->memory.memoryTypes[type].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
    const VkMemoryAllocateInfo ai = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = req.size, .memoryTypeIndex = (uint32_t)type };
    return vkAllocateMemory(r->device, &ai, NULL, out);
}

static VkResult build_pipeline(struct Renderer *r)
{
    VkShaderModule vert = VK_NULL_HANDLE, frag = VK_NULL_HANDLE;
    VkShaderModuleCreateInfo shader = { .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = sizeof triangle_vert, .pCode = triangle_vert };
    VkResult rc = vkCreateShaderModule(r->device, &shader, NULL, &vert);
    if (rc != VK_SUCCESS) return rc;
    shader.codeSize = sizeof triangle_frag;
    shader.pCode = triangle_frag;
    rc = vkCreateShaderModule(r->device, &shader, NULL, &frag);
    if (rc != VK_SUCCESS) goto done;
    const VkPipelineShaderStageCreateInfo stages[] = {
        { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
          .stage = VK_SHADER_STAGE_VERTEX_BIT, .module = vert, .pName = "main" },
        { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
          .stage = VK_SHADER_STAGE_FRAGMENT_BIT, .module = frag, .pName = "main" },
    };
    const VkPipelineVertexInputStateCreateInfo input = { .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO };
    const VkPipelineInputAssemblyStateCreateInfo assembly = { .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST };
    /* With positive height, clip y=-1 maps to stored row zero, which this ABI
     * defines as the bottom row. No winding test or fragment coordinates. */
    const VkViewport viewport = { .width = (float)r->width, .height = (float)r->height, .maxDepth = 1.0f };
    const VkRect2D rect = { .extent = {r->width, r->height} };
    const VkPipelineViewportStateCreateInfo vp = { .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
        .viewportCount = 1, .pViewports = &viewport, .scissorCount = 1, .pScissors = &rect };
    const VkPipelineRasterizationStateCreateInfo raster = { .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
        .polygonMode = VK_POLYGON_MODE_FILL, .cullMode = VK_CULL_MODE_NONE, .lineWidth = 1.0f };
    const VkPipelineMultisampleStateCreateInfo ms = { .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
        .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT };
    const VkPipelineColorBlendAttachmentState blend_attachment = { .colorWriteMask =
        VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT };
    const VkPipelineColorBlendStateCreateInfo blend = { .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
        .attachmentCount = 1, .pAttachments = &blend_attachment };
    const VkGraphicsPipelineCreateInfo pi = { .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .stageCount = 2, .pStages = stages, .pVertexInputState = &input, .pInputAssemblyState = &assembly,
        .pViewportState = &vp, .pRasterizationState = &raster, .pMultisampleState = &ms,
        .pColorBlendState = &blend, .layout = r->layout, .renderPass = r->pass };
    rc = vkCreateGraphicsPipelines(r->device, VK_NULL_HANDLE, 1, &pi, NULL, &r->pipeline);
done:
    vkDestroyShaderModule(r->device, frag, NULL);
    vkDestroyShaderModule(r->device, vert, NULL);
    return rc;
}

static void *open_renderer(unsigned w, unsigned h, char *error, size_t cap)
{
    const char *stage = "invalid dimensions (max 4096 per side, 4 Mi pixels)";
    VkResult rc = VK_SUCCESS;
    struct Renderer *r = NULL;
#define TRY(label, call) do { stage = (label); rc = (call); if (rc != VK_SUCCESS) goto fail; } while (0)
    if (!w || !h || w > 4096 || h > 4096 || w * h > 4194304) goto fail;
    stage = "renderer already open";
    if (live) goto fail;
    stage = "allocate renderer";
    if (!(r = calloc(1, sizeof(*r)))) goto fail;
    r->width = w;
    r->height = h;
    r->bytes = (size_t)w * h * 4;
    const VkApplicationInfo app = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "HackiOS GPU", .apiVersion = VK_API_VERSION_1_0 };
    const VkInstanceCreateInfo ii = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app };
    TRY("create Vulkan instance", vkCreateInstance(&ii, NULL, &r->instance));
    TRY("find RGBA8 Vulkan graphics device", choose_device(r));
    const float priority = 1.0f;
    const VkDeviceQueueCreateInfo qi = { .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = r->family, .queueCount = 1, .pQueuePriorities = &priority };
    const VkDeviceCreateInfo di = { .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1, .pQueueCreateInfos = &qi };
    TRY("create graphics device", vkCreateDevice(r->physical, &di, NULL, &r->device));
    vkGetDeviceQueue(r->device, r->family, 0, &r->queue);
    const VkImageCreateInfo image = { .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D,
        .format = RGBA, .extent = {w, h, 1}, .mipLevels = 1, .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL, .usage = USAGE };
    VkMemoryRequirements req;
    TRY("create RGBA8 image", vkCreateImage(r->device, &image, NULL, &r->image));
    vkGetImageMemoryRequirements(r->device, r->image, &req);
    TRY("allocate image memory", allocate(r, req, 0, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, &r->image_mem, NULL));
    TRY("bind image memory", vkBindImageMemory(r->device, r->image, r->image_mem, 0));
    const VkImageViewCreateInfo view = { .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = r->image, .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = RGBA,
        .subresourceRange = { .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT, .levelCount = 1, .layerCount = 1 } };
    TRY("create RGBA8 image view", vkCreateImageView(r->device, &view, NULL, &r->view));
    const VkBufferCreateInfo buf = { .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = r->bytes, .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT };
    TRY("create readback buffer", vkCreateBuffer(r->device, &buf, NULL, &r->read_buffer));
    vkGetBufferMemoryRequirements(r->device, r->read_buffer, &req);
    TRY("allocate readback memory", allocate(r, req, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
        VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, &r->read_mem, &r->coherent));
    TRY("bind readback buffer", vkBindBufferMemory(r->device, r->read_buffer, r->read_mem, 0));
    TRY("map readback buffer", vkMapMemory(r->device, r->read_mem, 0, VK_WHOLE_SIZE, 0, &r->mapped));
    const VkAttachmentDescription att = { .format = RGBA, .samples = VK_SAMPLE_COUNT_1_BIT,
        .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR, .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
        .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE, .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED, .finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL };
    const VkAttachmentReference ref = { 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL };
    const VkSubpassDescription sub = { .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
        .colorAttachmentCount = 1, .pColorAttachments = &ref };
    const VkSubpassDependency deps[] = {
        { .srcSubpass = VK_SUBPASS_EXTERNAL, .dstSubpass = 0,
          .srcStageMask = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
          .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
          .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT },
        { .srcSubpass = 0, .dstSubpass = VK_SUBPASS_EXTERNAL,
          .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
          .dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT,
          .srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT },
    };
    const VkRenderPassCreateInfo pass = { .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
        .attachmentCount = 1, .pAttachments = &att, .subpassCount = 1, .pSubpasses = &sub,
        .dependencyCount = 2, .pDependencies = deps };
    TRY("create render pass", vkCreateRenderPass(r->device, &pass, NULL, &r->pass));
    const VkFramebufferCreateInfo fb = { .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
        .renderPass = r->pass, .attachmentCount = 1, .pAttachments = &r->view, .width = w, .height = h, .layers = 1 };
    TRY("create framebuffer", vkCreateFramebuffer(r->device, &fb, NULL, &r->frame));
    const VkPushConstantRange constants = { .stageFlags = VK_SHADER_STAGE_VERTEX_BIT, .size = 12 * sizeof(float) };
    const VkPipelineLayoutCreateInfo li = { .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .pushConstantRangeCount = 1, .pPushConstantRanges = &constants };
    TRY("create pipeline layout", vkCreatePipelineLayout(r->device, &li, NULL, &r->layout));
    TRY("create triangle pipeline", build_pipeline(r));
    const VkCommandPoolCreateInfo pool = { .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, .queueFamilyIndex = r->family };
    TRY("create command pool", vkCreateCommandPool(r->device, &pool, NULL, &r->pool));
    const VkCommandBufferAllocateInfo commands = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = r->pool, .commandBufferCount = 1 };
    TRY("allocate command buffer", vkAllocateCommandBuffers(r->device, &commands, &r->cmd));
    const VkFenceCreateInfo fi = { .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    TRY("create fence", vkCreateFence(r->device, &fi, NULL, &r->fence));
    return live = r;
fail:
    if (error && cap) snprintf(error, cap, "%s (VkResult %d)", stage, (int)rc);
    destroy(r);
    return NULL;
#undef TRY
}

static int triangle(void *opaque, const float xy[6], const float rgba[4])
{
    struct Renderer *r = opaque;
    if (!r || !xy || !rgba || r->broken) return 0;
    for (int i = 0; i < 6; ++i) if (!isfinite(xy[i])) return 0;
    for (int i = 0; i < 4; ++i) if (!isfinite(rgba[i])) return 0;
    r->readable = false;
    float push[12] = {0};
    memcpy(push, xy, 6 * sizeof(float));
    memcpy(push + 8, rgba, 4 * sizeof(float));
    const VkCommandBufferBeginInfo begin = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT };
    if (vkResetFences(r->device, 1, &r->fence) != VK_SUCCESS ||
        vkBeginCommandBuffer(r->cmd, &begin) != VK_SUCCESS) goto fail;
    const VkClearValue clear = { .color.float32 = {0, 0, 0, 1} };
    const VkRenderPassBeginInfo pass = { .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
        .renderPass = r->pass, .framebuffer = r->frame, .renderArea.extent = {r->width, r->height},
        .clearValueCount = 1, .pClearValues = &clear };
    vkCmdBeginRenderPass(r->cmd, &pass, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(r->cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, r->pipeline);
    vkCmdPushConstants(r->cmd, r->layout, VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof push, push);
    vkCmdDraw(r->cmd, 3, 1, 0, 0);
    vkCmdEndRenderPass(r->cmd);
    const VkBufferImageCopy copy = { .imageSubresource = { .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT, .layerCount = 1 },
        .imageExtent = {r->width, r->height, 1} };
    vkCmdCopyImageToBuffer(r->cmd, r->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, r->read_buffer, 1, &copy);
    const VkBufferMemoryBarrier host = { .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT, .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .buffer = r->read_buffer, .size = VK_WHOLE_SIZE };
    vkCmdPipelineBarrier(r->cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                         0, 0, NULL, 1, &host, 0, NULL);
    const VkSubmitInfo submit = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount = 1, .pCommandBuffers = &r->cmd };
    if (vkEndCommandBuffer(r->cmd) != VK_SUCCESS || vkQueueSubmit(r->queue, 1, &submit, r->fence) != VK_SUCCESS ||
        vkWaitForFences(r->device, 1, &r->fence, VK_TRUE, UINT64_MAX) != VK_SUCCESS) goto fail;
    if (!r->coherent) {
        const VkMappedMemoryRange mr = { .sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
            .memory = r->read_mem, .size = VK_WHOLE_SIZE };
        if (vkInvalidateMappedMemoryRanges(r->device, 1, &mr) != VK_SUCCESS) goto fail;
    }
    r->readable = true;
    return 1;
fail:
    r->broken = true;
    return 0;
}

static int read_rgba(void *opaque, void *output, size_t bytes)
{
    struct Renderer *r = opaque;
    if (!r || !r->readable || !output || bytes < r->bytes) return 0;
    memcpy(output, r->mapped, r->bytes);
    return 1;
}

static const char *renderer_name(void *opaque)
{
    return opaque ? ((struct Renderer *)opaque)->name : NULL;
}

static void renderer_close(void *opaque)
{
    if (opaque && live == opaque) {
        live = NULL;
        destroy(opaque);
    }
}

const struct hios_renderer_v1 *hios_renderer_get(uint32_t abi)
{
    static const struct hios_renderer_v1 ops = { HIOS_RENDERER_ABI, sizeof ops, "vulkan-rgba8",
        open_renderer, renderer_name, triangle, read_rgba, renderer_close };
    return abi == HIOS_RENDERER_ABI ? &ops : NULL;
}
