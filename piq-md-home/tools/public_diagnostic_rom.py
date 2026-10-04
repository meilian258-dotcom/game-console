"""Original two-port 68000 test. No retail game/BIOS; reuse our hardware init only."""
import struct
from diagnostic_rom import create as base_rom

def create():
    rom=bytearray(base_rom())
    marker=bytes.fromhex('13fc004000a1000913fc000000a10003')
    offset=rom.index(marker,0x200)+len(marker)
    code=bytearray()
    def w(v):code.extend(struct.pack('>H',v))
    def l(v):code.extend(struct.pack('>I',v))
    def mb(v,a):w(0x13fc);w(v);l(a)
    def ml(v,a):w(0x23fc);l(v);l(a)
    def branch(op):w(op);at=len(code);w(0);return at
    jumps=[]
    mb(0x40,0xa1000b);mb(0,0xa10005)
    start=len(code);w(0x7200) # moveq #0,d1; resulting backdrop colour
    mb(0,0xa10003);w(0x1039);l(0xa10003);w(0x0200);w(0x10)
    a=branch(0x6700) # P1 A low TH
    w(0x0041);w(0x000e) # idle red
    b=branch(0x6000)
    jumps.append((a,len(code)));w(0x0041);w(0x00e0);mb(0x5a,0x200001) # P1 green
    jumps.append((b,len(code)))
    mb(0,0xa10005);w(0x1039);l(0xa10005);w(0x0200);w(0x10)
    a=branch(0x6600) # P2 not pressed
    w(0x0041);w(0x0e00);mb(0xa5,0x200003) # P2 adds blue; distinct SRAM location
    jumps.append((a,len(code)))
    ml(0xc0000000,0xc00004);w(0x33c1);l(0xc00000) # move.w d1,CRAM
    a=branch(0x6000);jumps.append((a,start))
    for at,target in jumps:code[at:at+2]=struct.pack('>h',target-at)
    rom[offset:]=bytes(len(rom)-offset);rom[offset:offset+len(code)]=code
    rom[0x120:0x130]=b'GC MD 2PORT TEST'
    checksum=sum(struct.unpack('>'+str((len(rom)-512)//2)+'H',rom[512:]))&0xffff
    rom[0x18e:0x190]=struct.pack('>H',checksum)
    return bytes(rom)
