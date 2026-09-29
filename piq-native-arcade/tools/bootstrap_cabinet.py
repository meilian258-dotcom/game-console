"""One-time reviewed FC lifecycle port. Uses apply_patch; refuses every existing destination.
Only standalone project files are added. Frozen FC sources/resources are never modified.
"""
from pathlib import Path
import re,subprocess
ROOT=Path(__file__).resolve().parents[1]
FC=ROOT.parent/'piq-fc-arcade'
CODEX='C:/Users/13498/AppData/Local/OpenAI/Codex/bin/7a4cbea4d249f245/codex.exe'

def add(path,text):
    p=ROOT/path
    if p.exists():raise RuntimeError('Refusing existing destination '+str(p))
    patch='*** Begin Patch\n*** Add File: '+p.as_posix()+'\n'+''.join('+'+line+'\n' for line in text.splitlines())+'*** End Patch\n'
    subprocess.run([CODEX,'--codex-run-as-apply-patch',patch],check=True,cwd=ROOT)

def rename(text):
    text=text.replace('cn.piq.fcarcade','cn.piq.nativearcade').replace('DualCabinet','NativeCabinet').replace('FcArcadeBlock','NativeCabinetBlock')
    text=text.replace('import cn.piq.nativearcade.registry.ModBlockEntities;','import cn.piq.nativearcade.registry.NativeArcadeRegistries;')
    text=text.replace('import cn.piq.nativearcade.registry.ModBlocks;','import cn.piq.nativearcade.registry.NativeArcadeRegistries;')
    text=text.replace('import cn.piq.nativearcade.registry.ModItems;','import cn.piq.nativearcade.registry.NativeArcadeRegistries;')
    text=text.replace('ModBlockEntities.DUAL_CABINET_PART','NativeArcadeRegistries.PART_ENTITY').replace('ModBlockEntities.DUAL_CABINET','NativeArcadeRegistries.CABINET_ENTITY')
    text=text.replace('ModBlocks.DUAL_CABINET_PART','NativeArcadeRegistries.CABINET_PART').replace('ModBlocks.DUAL_CABINET','NativeArcadeRegistries.CABINET')
    text=text.replace('ModItems.DUAL_CABINET','NativeArcadeRegistries.CABINET_ITEM')
    text=text.replace('piq_fc_arcade','piq_native_arcade').replace('dual_cabinet','cabinet')
    text=text.replace('Twelve','Six').replace('twelve','six').replace('2 wide, 3 high, 2 deep','2 wide, 3 high, 1 deep')
    # Exactly six identities; the FC legacy reserved rear row is not inherited.
    text=re.sub(r'\b4095\b','63',text)
    text=re.sub(r'\b12\b','6',text)
    text=text.replace('IntegerProperty.create("part", 1, 11)','IntegerProperty.create("part", 1, 5)')
    # Avoid duplicate same-imports after the three registry classes collapse.
    seen=set();out=[]
    for line in text.splitlines():
        if line.startswith('import '):
            if line in seen:continue
            seen.add(line)
        out.append(line)
    return '// SPDX-License-Identifier: GPL-3.0-or-later\n// Adapted from PIQ FC DualCabinet lifecycle; isolated native cabinet IDs and six-cell ledger.\n'+'\n'.join(out)+'\n'

def main():
    originals=['Footprint','AssemblyLedger','AssemblyData','RemovalGate','PartBlockEntity','PartBlock','BlockItem','Structure']
    for suffix in originals:
        src=FC/'src/main/java/cn/piq/fcarcade/world'/('DualCabinet'+suffix+'.java');s=rename(src.read_text(encoding='utf-8'))
        if suffix=='Footprint':
            s=s.replace('int x = part % 2, y = (part / 2) % 3, z = part / 6;','int x = part % 2, y = part / 2, z = 0;')
            s=s.replace('// Keep legacy proxy identities; only the occupied 5.6 units of the\n        // third row collide. The reserved rear row remains empty.','// Only the occupied 5.6 units of the third row collide.')
        if suffix=='AssemblyData':s=s.replace('"piq_cabinet_assemblies"','"piq_native_arcade_cabinet_assemblies"').replace('"piq_dual_cabinet_assemblies"','"piq_native_arcade_cabinet_assemblies"')
        if suffix=='PartBlock':
            s=s.replace('    @Override protected InteractionResult useWithoutItem', '    @Override protected java.util.List<ItemStack> getDrops(BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder context) { return java.util.List.of(); }\n    @Override protected InteractionResult useWithoutItem')
        if suffix=='Structure':
            s=s.replace('import cn.piq.nativearcade.server.ServerArcadeSessions;','import cn.piq.nativearcade.events.NativeCabinetUseEvent;\nimport cn.piq.nativearcade.events.NativeCabinetClosedEvent;')
            s=s.replace('        if (player.isShiftKeyDown()) ServerArcadeSessions.openLibrary(player, anchor);\n        else ServerArcadeSessions.interact(player, anchor);','        if (!(cabinet instanceof NativeCabinetBlockEntity machine)) return InteractionResult.FAIL;\n        NeoForge.EVENT_BUS.post(new NativeCabinetUseEvent(level, player, anchor, machine.assemblyId()));')
            start=s.index('        try { ServerArcadeSessions.stopHomeConsole')
            end=s.index('\n    }\n    private static boolean validPart',start)
            s=s[:start]+'''        try { NeoForge.EVENT_BUS.post(new NativeCabinetClosedEvent(level, anchor, expected)); }
        catch (RuntimeException error) {
            cn.piq.nativearcade.NativeArcadeMod.LOGGER.warn("[PIQ Native] Cabinet session close callback failed at {}", anchor, error);
        }'''+s[end:]
            s=s.replace('        BlockPos anchor = resolveAnchor(level, clicked);','        if (player.distanceToSqr(clicked.getX()+.5,clicked.getY()+.5,clicked.getZ()+.5)>64) return InteractionResult.FAIL;\n        BlockPos anchor = resolveAnchor(level, clicked);')
        add(Path('src/main/java/cn/piq/nativearcade/world')/('NativeCabinet'+suffix+'.java'),s)

if __name__=='__main__':main()
