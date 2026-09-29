param(
    [Parameter(Mandatory = $true)][string]$OriginalPath,
    [Parameter(Mandatory = $true)][string]$AiPath,
    [Parameter(Mandatory = $true)][string]$ModelPath,
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [Parameter(Mandatory = $true)][string]$BackupDirectory,
    [Parameter(Mandatory = $true)][string]$SourceArchiveDirectory
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$expectedOriginal = '688CE2C45B9A80BD2EE70AA41C4552D3725860594E7C3323828A5BF465CE4E68'
$expectedAi = '8C5C1A3A5BF7819C119B5620B3E12E8492A1DC81AF05412DE6E8849AD2730D4B'
if ((Get-FileHash -LiteralPath $OriginalPath -Algorithm SHA256).Hash -ne $expectedOriginal) {
    throw 'Original must be the unchanged 2048 race atlas, not a previous normalized output.'
}
if ((Get-FileHash -LiteralPath $AiPath -Algorithm SHA256).Hash -ne $expectedAi) {
    throw 'Unexpected AI source: crop coordinates are specific to the approved 1254 image.'
}
if (Test-Path -LiteralPath $BackupDirectory) { throw 'Use a fresh backup directory; existing backups are never overwritten.' }
[IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($BackupDirectory)) | Out-Null
$backup = Join-Path $BackupDirectory 'race-original.png'
Copy-Item -LiteralPath $OriginalPath -Destination $backup
[IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($SourceArchiveDirectory)) | Out-Null
$archivedAi = Join-Path $SourceArchiveDirectory 'ai-generic-arcade-original-1254.png'
if (Test-Path -LiteralPath $archivedAi) {
    if ((Get-FileHash -LiteralPath $archivedAi -Algorithm SHA256).Hash -ne $expectedAi) {
        throw 'Refusing to overwrite a different archived AI source.'
    }
} else { Copy-Item -LiteralPath $AiPath -Destination $archivedAi }

$drawingReferences = [AppDomain]::CurrentDomain.GetAssemblies() | Where-Object {
    $_.GetName().Name -in @('System.Drawing.Common','System.Drawing.Primitives',
            'System.Private.Windows.GdiPlus','System.Private.Windows.Core')
} | ForEach-Object { $_.Location }
Add-Type -ReferencedAssemblies $drawingReferences -TypeDefinition @"
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;

public sealed class RocketAtlasNormalizer : IDisposable {
    public readonly Bitmap Original;
    public readonly Bitmap Ai;
    public readonly Bitmap Result;
    public readonly bool[] Allowed = new bool[2048 * 2048];
    public int ChangedPixels;
    public int OutsideChangedPixels;
    public int BottomChangedPixels;
    public int BlackScreenPixels;
    public RocketAtlasNormalizer(string original, string ai) {
        Original = new Bitmap(original);
        Ai = new Bitmap(ai);
        if (Original.Width != 2048 || Original.Height != 2048 || Ai.Width != 1254 || Ai.Height != 1254)
            throw new InvalidOperationException("Input dimensions do not match the approved assets.");
        Result = Original.Clone(new Rectangle(0, 0, 2048, 2048), PixelFormat.Format32bppArgb);
    }
    void Mark(int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) Allowed[y * 2048 + x] = true;
    }
    public void ClearArt(int x0, int y0, int x1, int y1) {
        Mark(x0, y0, x1, y1);
        // Remove all old racing graphics, including unused bleed outside each
        // large UV island. The original atlas background itself is unchanged.
        using (Graphics g = Graphics.FromImage(Result))
        using (Brush b = new SolidBrush(Original.GetPixel(0, 0))) g.FillRectangle(b, x0, y0, x1-x0, y1-y0);
    }
    static double Clamp(double value, double min, double max) { return Math.Max(min, Math.Min(max, value)); }
    Color Metal(double y, double z) {
        // The AI material is sampled from its neutral metal panel, not its
        // displaced miniature mechanical islands. Both sides share world Y/Z.
        int sx = 909 + (int)Clamp(z / 16.0 * 161.0, 0, 161);
        int sy = 88 + (int)Clamp((32.0 - y) / 32.0 * 364.0, 0, 364);
        Color c = Ai.GetPixel(sx, sy);
        return Color.FromArgb(255, (int)(c.R * 0.53), (int)(c.G * 0.53), (int)(c.B * 0.53));
    }
    public void Side(double u0, double v0, double u1, double v1,
            double y0, double y1, double z0, double z1, bool east,
            double angle, double pivotY, double pivotZ) {
        int left=(int)Math.Floor(u0)-2, top=(int)Math.Floor(v0)-2;
        int right=(int)Math.Ceiling(u1)+2, bottom=(int)Math.Ceiling(v1)+2;
        double radians=angle*Math.PI/180, cosine=Math.Cos(radians), sine=Math.Sin(radians);
        for (int py=top; py<bottom; py++) for (int px=left; px<right; px++) {
            if (!Allowed[py*2048+px]) throw new InvalidOperationException("Side UV exceeds its approved art island.");
            double fu=(px+0.5-u0)/(u1-u0), fv=(py+0.5-v0)/(v1-v0);
            double localY=y1-fv*(y1-y0);
            // Minecraft FaceInfo: EAST U grows maxZ -> minZ; WEST is the reverse.
            double localZ=east ? z1-fu*(z1-z0) : z0+fu*(z1-z0);
            double wy=pivotY+(localY-pivotY)*cosine-(localZ-pivotZ)*sine;
            double wz=pivotZ+(localY-pivotY)*sine+(localZ-pivotZ)*cosine;
            Color metal=Metal(wy,wz);
            double center=10.8+Clamp((24.0-wy)/5.0,0,1)*1.1;
            double distance=Math.Min(Math.Abs(wz-center), Math.Abs(wz-(center+0.36)));
            double coverage=Clamp((0.13-distance)/0.045,0,1);
            Result.SetPixel(px,py,Color.FromArgb(255,
                (int)(metal.R*(1-coverage)+20*coverage),
                (int)(metal.G*(1-coverage)+213*coverage),
                (int)(metal.B*(1-coverage)+232*coverage)));
        }
    }
    public void Crop(int sx, int sy, int sw, int sh, double x0, double y0, double x1, double y1) {
        int left=(int)Math.Floor(x0)-2, top=(int)Math.Floor(y0)-2;
        int right=(int)Math.Ceiling(x1)+2, bottom=(int)Math.Ceiling(y1)+2;
        Mark(left,top,right,bottom);
        using (Bitmap crop=Ai.Clone(new Rectangle(sx,sy,sw,sh),PixelFormat.Format32bppArgb))
        using (Graphics g=Graphics.FromImage(Result))
        using (ImageAttributes attributes=new ImageAttributes()) {
            g.CompositingMode=CompositingMode.SourceCopy;
            g.InterpolationMode=InterpolationMode.HighQualityBicubic;
            g.PixelOffsetMode=PixelOffsetMode.HighQuality;
            attributes.SetWrapMode(WrapMode.TileFlipXY);
            g.DrawImage(crop,new Rectangle(left,top,right-left,bottom-top),0,0,sw,sh,GraphicsUnit.Pixel,attributes);
        }
    }
    public void BlackScreen() {
        Mark(918,278,1259,535);
        using (Graphics g=Graphics.FromImage(Result)) {
            g.CompositingMode=CompositingMode.SourceCopy;
            g.FillRectangle(Brushes.Black,918,278,341,257);
        }
    }
    public void VerifyAndSave(string output) {
        for (int y=0; y<2048; y++) for (int x=0; x<2048; x++) {
            Color before=Original.GetPixel(x,y), after=Result.GetPixel(x,y);
            if (before.ToArgb()!=after.ToArgb()) {
                ChangedPixels++;
                if (!Allowed[y*2048+x]) OutsideChangedPixels++;
                if (y>=1248) BottomChangedPixels++;
            }
            if (x>=918 && x<1259 && y>=278 && y<535) {
                if (after.ToArgb()!=Color.Black.ToArgb()) throw new InvalidOperationException("Screen must be exact opaque black.");
                BlackScreenPixels++;
            }
        }
        if (OutsideChangedPixels!=0 || BottomChangedPixels!=0) throw new InvalidOperationException("Preserved pixels changed.");
        Result.Save(output,ImageFormat.Png);
        using (Bitmap verify=new Bitmap(output)) {
            if (verify.Width!=2048 || verify.Height!=2048) throw new InvalidOperationException("Output dimensions changed.");
            for (int y=0; y<2048; y++) for (int x=0; x<2048; x++)
                if (verify.GetPixel(x,y).ToArgb()!=Result.GetPixel(x,y).ToArgb())
                    throw new InvalidOperationException("PNG round-trip changed pixels.");
        }
    }
    public void Dispose() { Result.Dispose(); Ai.Dispose(); Original.Dispose(); }
}
"@

$model = Get-Content -Raw -LiteralPath $ModelPath | ConvertFrom-Json
$normalizer = [RocketAtlasNormalizer]::new($backup, $archivedAi)
$painted = [Collections.Generic.List[object]]::new()
try {
    $artRegions = @(@(60,124,420,852), @(484,124,844,852), @(44,876,324,1184), @(484,876,776,1184),
            @(182,1205,246,1233), @(564,1205,628,1233))
    foreach ($rect in $artRegions) { $normalizer.ClearArt($rect[0],$rect[1],$rect[2],$rect[3]) }
    foreach ($element in $model.elements) {
        foreach ($face in $element.faces.PSObject.Properties) {
            $uv=$face.Value.uv
            if ($face.Name -notin @('east','west') -or $uv.Count -ne 4 -or
                    [double]$uv[0] -ge 7 -or [double]$uv[2] -ge 7 -or [double]$uv[1] -ge 9.7) { continue }
            $angle=0.0; $pivotY=0.0; $pivotZ=0.0
            if ($null -ne $element.rotation) {
                if ($element.rotation.axis -ne 'x' -or $element.rotation.rescale) { throw 'Unexpected side rotation.' }
                $angle=[double]$element.rotation.angle
                $pivotY=[double]$element.rotation.origin[1]; $pivotZ=[double]$element.rotation.origin[2]
            }
            $normalizer.Side($uv[0]*128,$uv[1]*128,$uv[2]*128,$uv[3]*128,
                $element.from[1],$element.to[1],$element.from[2],$element.to[2],
                ($face.Name -eq 'east'),$angle,$pivotY,$pivotZ)
            $painted.Add([ordered]@{name=$element.name; face=$face.Name; uv=$uv;
                    from=$element.from; to=$element.to; rotation=$element.rotation})
        }
    }
    if ($painted.Count -ne 20) { throw "Expected 18 large side faces and two nose underside art faces, found $($painted.Count)." }
    # The racing source has unused colored bleed beyond these three UV islands.
    # Clear it as part of the same decoration, then add fresh 2px sampling bleed.
    $decorationBleedBounds = @(@(912,128,1322,234), @(910,270,1267,543), @(912,580,1344,729))
    foreach ($rect in $decorationBleedBounds) { $normalizer.ClearArt($rect[0],$rect[1],$rect[2],$rect[3]) }
    $normalizer.Crop(565,85,236,48,920,136,1312.32,224.32)
    $normalizer.Crop(560,362,258,77,920,588,1336,720.8)
    $normalizer.BlackScreen()
    $normalizer.VerifyAndSave([IO.Path]::GetFullPath($OutputPath))
    Copy-Item -LiteralPath $OutputPath -Destination (Join-Path $SourceArchiveDirectory '通用街机默认皮肤-2048.png')
    $audit=[ordered]@{
        output=[IO.Path]::GetFullPath($OutputPath); width=2048; height=2048
        sha256=(Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash
        originalSha256=$expectedOriginal; aiSha256=$expectedAi
        modelSha256=(Get-FileHash -LiteralPath $ModelPath -Algorithm SHA256).Hash
        originalBackup=[IO.Path]::GetFullPath($backup); aiArchive=[IO.Path]::GetFullPath($archivedAi)
        modifiedArtBounds=$artRegions; sideFaceCount=$painted.Count; sideFaces=$painted
        decorationBleedBounds=$decorationBleedBounds
        marqueeUvPixels=@(920,136,1312.32,224.32); consoleUvPixels=@(920,588,1336,720.8)
        cropPaddingPixels=2; blackScreenBounds=@(918,278,1259,535); blackScreenRgba=@(0,0,0,255)
        blackScreenPixelCount=$normalizer.BlackScreenPixels; changedPixels=$normalizer.ChangedPixels
        changedOutsideAllowedRegions=$normalizer.OutsideChangedPixels
        changedBottomMechanicalPixels=$normalizer.BottomChangedPixels
        preservedBottomFromY=1248; pngRoundTripExact=$true
        material='Approved AI neutral metal crop; shared world Y/Z cyan stripe projection across rotated side faces.'
    }
    $audit | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $SourceArchiveDirectory 'generic-atlas-audit.json') -Encoding utf8
    [pscustomobject]$audit | Select-Object output,width,height,sha256,sideFaceCount,blackScreenPixelCount,changedPixels,
        changedOutsideAllowedRegions,changedBottomMechanicalPixels,pngRoundTripExact | ConvertTo-Json
} finally { $normalizer.Dispose() }
