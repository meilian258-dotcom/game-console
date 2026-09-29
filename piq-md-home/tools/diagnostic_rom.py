"""Original 68000 diagnostic, no retail ROM/BIOS. A changes CRAM and SRAM; PSG tone."""
import struct
def create():
    rom=bytearray(32768);rom[:8]=struct.pack('>II',0xffff00,0x200)
    for i in range(8,0x100,4):rom[i:i+4]=struct.pack('>I',0x200)
    rom[0x100:0x110]=b'SEGA MEGA DRIVE '
    rom[0x120:0x130]=b'PIQ MD TEST     '
    rom[0x180:0x18e]=b'GM PIQ00000-00'
    rom[0x190:0x192]=b'J6';rom[0x1a0:0x1a8]=struct.pack('>II',0,len(rom)-1)
    rom[0x1b0:0x1bc]=b'RA\xf8\x20'+struct.pack('>II',0x200001,0x203fff)
    rom[0x1f0:0x1f3]=b'JUE'
    code=bytearray()
    def w(v):code.extend(struct.pack('>H',v))
    def l(v):code.extend(struct.pack('>I',v))
    def mw(v,addr):w(0x33fc);w(v);l(addr)
    def mb(v,addr):w(0x13fc);w(v);l(addr)
    def ml(v,addr):w(0x23fc);l(v);l(addr)
    w(0x46fc);w(0x2700) # supervisor, interrupts masked
    # The pinned md1va3 profile has no TMSS, and must not receive a TMSS write.
    mw(0x100,0xa11100) # Z80 bus request
    for reg,val in [(0,4),(1,0x44),(2,0x30),(3,0x3c),(4,7),(5,0x6c),(7,0),(10,255),(11,0),(12,0x81),(13,0x3f),(15,2),(16,1)]:mw(0x8000|(reg<<8)|val,0xc00004)
    mb(0x9f,0xc00011);mb(0x81,0xc00011);mb(0x10,0xc00011);mb(0x90,0xc00011)
    mb(0x40,0xa10009);mb(0,0xa10003)
    start=len(code)
    w(0x1039);l(0xa10003) # input low TH: bit4=A
    w(0x0200);w(0x10)
    w(0x6700);branch=len(code);w(0)
    ml(0xc0000000,0xc00004);mw(0x000e,0xc00000) # red
    w(0x6000);skip=len(code);w(0)
    pressed=len(code)
    ml(0xc0000000,0xc00004);mw(0x00e0,0xc00000);mb(0x5a,0x200001)
    end=len(code)
    w(0x6000);back=len(code);w(0)
    for at,target in [(branch,pressed),(skip,end),(back,start)]:code[at:at+2]=struct.pack('>h',target-at)
    rom[0x200:0x200+len(code)]=code
    checksum=sum(struct.unpack('>'+str((len(rom)-512)//2)+'H',rom[512:]))&0xffff
    rom[0x18e:0x190]=struct.pack('>H',checksum)
    return bytes(rom)
