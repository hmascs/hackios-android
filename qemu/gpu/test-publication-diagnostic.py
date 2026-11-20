#!/usr/bin/env python3
import importlib.util
import math
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('publication',Path(__file__).with_name('publication-diagnostic.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class DiagnosticTests(unittest.TestCase):
    def measure(self):
        def boundary(t,count,thermal):
            return dict(t=t+thermal/2,t_before=t,t_after=t+thermal,
                clock_bracket_seconds=thermal,source_t=dict(logs=t+.001,ring=t+.002,cpu=t+.003,thermal=t+thermal),
                readbacks=count,ring_seq=count,cpu=count,readback_resets=0)
        return dict(start=boundary(100,10,.2),end=boundary(220,130,.4),failure=None)
    def test_slow_thermal_still_yields_conservative_count_bounds(self):
        result=module.diagnostic(self.measure());row=result['sources']['ring_seq']
        self.assertAlmostEqual(row['nominal_per_second'],1)
        self.assertLess(row['per_second_bracket'][0],1)
        self.assertGreater(row['per_second_bracket'][1],1)
        self.assertFalse(result['causal_comparison_approved']);self.assertIsNone(result['actual_content_fps'])
    def test_bad_order_or_nan_is_not_salvaged(self):
        for value in (math.nan,50):
            data=self.measure();data['end']['source_t']['ring']=value
            with self.assertRaises(ValueError):module.diagnostic(data)
    def test_reset_or_regression_is_rejected(self):
        data=self.measure();data['end']['readback_resets']=1
        with self.assertRaises(ValueError):module.diagnostic(data)
        data=self.measure();data['end']['ring_seq']=0
        with self.assertRaises(ValueError):module.diagnostic(data)

if __name__=='__main__':unittest.main()
