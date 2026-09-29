from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch
from vintage_tv_alpha10_archive import ARCHIVE,release
from check_vintage_tv_presentation import analyze,probe

class HistoricalVintageTvTest(unittest.TestCase):
    def test_changed_archive_is_rejected_before_any_zip_parsing(self):
        with patch.object(Path,'read_bytes',return_value=b'wrong archive'):
            with self.assertRaises(ValueError):release()

    def test_historical_analysis_never_reads_active_resources_or_active_java(self):
        original=Path.read_bytes
        def guarded(path):
            if path!=ARCHIVE:raise AssertionError('Historical verifier read active file: '+str(path))
            return original(path)
        with patch.object(Path,'read_bytes',guarded):
            report,*_=analyze()
        self.assertTrue(report['ok'],report['checks'])

    def test_java_probe_uses_only_frozen_archive_and_probe_source(self):
        with patch('check_vintage_tv_presentation.subprocess.run',return_value=subprocess.CompletedProcess([],0,'','')) as run:
            probe()
        compile_args=run.call_args_list[0].args[0]
        self.assertEqual(str(ARCHIVE),compile_args[compile_args.index('-cp')+1])
        sources=[p for p in compile_args if p.endswith('.java')]
        self.assertEqual(1,len(sources));self.assertTrue(sources[0].endswith('VintageTvPresentationProbe.java'))
        run_args=run.call_args_list[1].args[0]
        self.assertTrue(run_args[run_args.index('-cp')+1].endswith(';'+str(ARCHIVE)))

if __name__=='__main__':unittest.main()
