"""One-time six-cell test port; all destinations are preflighted by bootstrap.add."""
from pathlib import Path
import re
from bootstrap_cabinet import ROOT,FC,add

for suffix in ('AssemblyLedgerTest','RemovalGateTest'):
    s=(FC/'src/test/java/cn/piq/fcarcade/world'/('DualCabinet'+suffix+'.java')).read_text(encoding='utf-8')
    s=s.replace('cn.piq.fcarcade','cn.piq.nativearcade').replace('DualCabinet','NativeCabinet')
    for before,after in ((4096,64),(4095,63),(255,15),(12,6),(11,5)):
        s=re.sub(r'\b'+str(before)+r'\b',str(after),s)
    s=s.replace('Twelve','Six').replace('Eleven','Five')
    s=s.replace('cancelPlacement(old,5,1,2,1)','cancelPlacement(old,5,1,2,0)')
    s=s.replace('eightAcknowledgedCellsStillRetainFourUnloadedCells','fourAcknowledgedCellsStillRetainTwoUnloadedCells')
    s=s.replace('part<8','part<4').replace('part=8','part=4')
    add(Path('src/test/java/cn/piq/nativearcade/world')/('NativeCabinet'+suffix+'.java'),s)
