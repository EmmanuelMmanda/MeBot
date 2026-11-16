#!/usr/bin/env python3
"""Offline gallery provenance and deterministic rendering regression checks."""
from pathlib import Path
import copy
import json
import runpy
import unittest
ROOT=Path(__file__).resolve().parents[1]
gallery=runpy.run_path(str(ROOT/'scripts/build-jobs-gallery.py'))
class GalleryTest(unittest.TestCase):
    def setUp(self):
        self.data=json.loads((ROOT/'docs/assets/jobs-screens.json').read_text())
    def test_exact_assets(self):
        gallery['validate'](self.data,ROOT/'docs')
    def test_render_is_current_and_deterministic(self):
        rendered=gallery['render'](self.data)
        self.assertEqual(rendered,gallery['render'](copy.deepcopy(self.data)))
        self.assertIn(rendered,(ROOT/'docs/jobs.html').read_text())
        self.assertEqual(rendered.count('<img '),6)
    def test_bad_provenance_rejected(self):
        for key,value in [('syntheticData',False),('liveAiRequest',True),('sourceHead','unknown')]:
            with self.subTest(key=key):
                d=copy.deepcopy(self.data);d[key]=value
                with self.assertRaises(AssertionError): gallery['validate'](d,ROOT/'docs')
    def test_asset_mismatch_duplicate_and_traversal_rejected(self):
        mutations=[('sha256','0'*64),('width',1),('path','../README.md'),('visuallyReviewed',False)]
        for key,value in mutations:
            with self.subTest(key=key):
                d=copy.deepcopy(self.data);d['screens'][0][key]=value
                with self.assertRaises(AssertionError): gallery['validate'](d,ROOT/'docs')
        d=copy.deepcopy(self.data);d['screens'][1]=copy.deepcopy(d['screens'][0])
        with self.assertRaises(AssertionError): gallery['validate'](d,ROOT/'docs')
if __name__=='__main__': unittest.main()
