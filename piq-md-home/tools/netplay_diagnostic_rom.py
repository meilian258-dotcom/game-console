"""Original MD FM/PSG, dual six-button handshake and SRAM test cartridge.
No commercial program/data. ROM-visible pad samples live at FF0010/FF0020.
"""
import struct
from diagnostic_rom import create as base

def create():
    rom=bytearray(base()); rom[0x200:]=bytes(len(rom)-0x200)
    code=bytearray()
    def w(n):code.extend(struct.pack('>H',n&65535))
    def l(n):code.extend(struct.pack('>I',n))
    def mb(v,a):w(0x13fc);w(v);l(a)
    def mw(v,a):w(0x33fc);w(v);l(a)
    def ml(v,a):w(0x23fc);l(v);l(a)
    def fm(r,v):mb(r,0xa04000);mb(v,0xa04001)
    def branch(op,target):w(op);at=len(code);w(target-at)
    w(0x46fc);w(0x2700)
    w(0x1039);l(0xa10001);w(0x0200);w(15);w(0x670a);ml(0x53454741,0xa14000)
    mw(0x100,0xa11100);mw(0x100,0xa11200) # request bus AND release reset: FM map becomes accessible
    for reg,val in [(0,4),(1,0x44),(2,0x30),(3,0x3c),(4,7),(5,0x6c),(7,0),(10,255),(11,0),(12,0x81),(13,0x3f),(15,2),(16,1)]:mw(0x8000|reg<<8|val,0xc00004)
    for v in [0x9f,0x81,0x10,0x90]:mb(v,0xc00011)
    fm(0x22,8);fm(0x27,0);fm(0xb0,7);fm(0xb4,0xc0)
    for slot in [0,4,8,12]:
        for r,v in [(0x30,1),(0x40,24),(0x50,31),(0x60,7),(0x70,0),(0x80,15)]:fm(r+slot,v)
    fm(0xa4,0x22);fm(0xa0,0x69);fm(0x28,0xf0)
    mb(0x40,0xa10009);mb(0x40,0xa1000b)
    start=len(code)
    # First leave VBlank, then wait for next VBlank. This allows six-button timeout.
    for branchop in [0x6600,0x6700]:
        loop=len(code);w(0x0839);w(3);l(0xc00005);branch(branchop,loop)
    w(0x5239);l(0xff0001)
    for port in range(2):
        a=0xa10003+port*2
        # Capture normal low/high plus every extended handshake phase.
        for i in range(8):
            mb(0x40 if i%2 else 0,a)
            for _ in range(3):w(0x4e71)
            w(0x1039);l(a);w(0x13c0);l(0xff0010+port*16+i)
        w(0x13c0);l(0x200001+port*2)
    # Input-dependent FM frequency and video colour provide semantic AV evidence.
    mb(0xa0,0xa04000);w(0x1039);l(0xff0011);w(0x13c0);l(0xa04001)
    ml(0xc0000000,0xc00004);w(0x3039);l(0xff0010);w(0x33c0);l(0xc00000)
    branch(0x6000,start)
    rom[0x200:0x200+len(code)]=code
    rom[0x18e:0x190]=struct.pack('>H',sum(struct.unpack('>'+str((len(rom)-512)//2)+'H',rom[512:]))&65535)
    return bytes(rom)
