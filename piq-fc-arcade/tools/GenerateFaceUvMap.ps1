param(
    [string]$ObjPath = '',
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\design\skins\UV_FaceMap_v2.png')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if ([string]::IsNullOrWhiteSpace($ObjPath)) {
    $ObjPath = Join-Path $PSScriptRoot '..\src\main\resources\assets\piq_fc_arcade\models\block\legacy_generic_machine.obj'
}

function Convert-HsvToColor {
    param(
        [double]$Hue,
        [double]$Saturation = 0.76,
        [double]$Value = 0.92
    )

    $chroma = $Value * $Saturation
    $sector = ($Hue % 360.0) / 60.0
    $secondary = $chroma * (1.0 - [Math]::Abs(($sector % 2.0) - 1.0))
    $red = 0.0
    $green = 0.0
    $blue = 0.0

    if ($sector -lt 1.0) {
        $red = $chroma
        $green = $secondary
    } elseif ($sector -lt 2.0) {
        $red = $secondary
        $green = $chroma
    } elseif ($sector -lt 3.0) {
        $green = $chroma
        $blue = $secondary
    } elseif ($sector -lt 4.0) {
        $green = $secondary
        $blue = $chroma
    } elseif ($sector -lt 5.0) {
        $red = $secondary
        $blue = $chroma
    } else {
        $red = $chroma
        $blue = $secondary
    }

    $match = $Value - $chroma
    return [System.Drawing.Color]::FromArgb(
        255,
        [Math]::Round(($red + $match) * 255.0),
        [Math]::Round(($green + $match) * 255.0),
        [Math]::Round(($blue + $match) * 255.0)
    )
}

$materialCodes = @{
    Screen       = 'S'
    ScreenBorder = 'SB'
    RightSide    = 'R'
    LeftSide     = 'L'
    Controller   = 'C'
    Front        = 'F'
    Top          = 'T'
    Back         = 'B'
    Bottom       = 'D'
    Bezel        = 'Z'
    Coin         = 'N'
}

$materialHues = @{
    Screen       = 205
    ScreenBorder = 320
    RightSide    = 175
    LeftSide     = 95
    Controller   = 45
    Front        = 15
    Top          = 275
    Back         = 225
    Bottom       = 350
    Bezel        = 125
    Coin         = 0
}

$objFullPath = [System.IO.Path]::GetFullPath($ObjPath)
$outputFullPath = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($outputFullPath)) | Out-Null

$textureCoordinates = @()
$faces = @()
$currentMaterial = ''
$materialFaceIndex = @{}

foreach ($line in [System.IO.File]::ReadLines($objFullPath)) {
    if ($line.StartsWith('vt ')) {
        $parts = $line.Split(' ', [System.StringSplitOptions]::RemoveEmptyEntries)
        $textureCoordinates += ,@(
            [double]::Parse($parts[1], [Globalization.CultureInfo]::InvariantCulture),
            [double]::Parse($parts[2], [Globalization.CultureInfo]::InvariantCulture)
        )
    } elseif ($line.StartsWith('usemtl ')) {
        $currentMaterial = $line.Substring(7).Trim()
        $materialFaceIndex[$currentMaterial] = 0
    } elseif ($line.StartsWith('f ')) {
        $materialFaceIndex[$currentMaterial]++
        $points = @()
        foreach ($token in $line.Substring(2).Split(' ', [System.StringSplitOptions]::RemoveEmptyEntries)) {
            $indices = $token.Split('/')
            $uv = $textureCoordinates[[int]$indices[1] - 1]
            $points += New-Object System.Drawing.PointF(
                [float]($uv[0] * 512.0),
                [float]((1.0 - $uv[1]) * 512.0)
            )
        }
        $faces += [pscustomobject]@{
            Material = $currentMaterial
            Index = $materialFaceIndex[$currentMaterial]
            Points = [System.Drawing.PointF[]]$points
        }
    }
}

$bitmap = New-Object System.Drawing.Bitmap 512, 512
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.Clear([System.Drawing.Color]::FromArgb(255, 18, 20, 27))
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None
$graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::SingleBitPerPixelGridFit

$outlinePen = New-Object System.Drawing.Pen ([System.Drawing.Color]::Black), 1
$whiteBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
$blackBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::Black)
$vertexBrushes = @(
    (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 255, 40, 40))),
    (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 50, 235, 80))),
    (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 40, 120, 255))),
    (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 255, 220, 35)))
)
$labelFont = New-Object System.Drawing.Font 'Arial', 8, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
$smallFont = New-Object System.Drawing.Font 'Arial', 6, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)

foreach ($face in $faces) {
    $baseHue = if ($materialHues.ContainsKey($face.Material)) { $materialHues[$face.Material] } else { 0 }
    $hue = ($baseHue + (($face.Index - 1) * 17)) % 360
    $value = if (($face.Index % 2) -eq 0) { 0.76 } else { 0.94 }
    $fillBrush = New-Object System.Drawing.SolidBrush (Convert-HsvToColor -Hue $hue -Value $value)
    $graphics.FillPolygon($fillBrush, $face.Points)
    $graphics.DrawPolygon($outlinePen, $face.Points)
    $fillBrush.Dispose()

    $minX = ($face.Points | Measure-Object -Property X -Minimum).Minimum
    $maxX = ($face.Points | Measure-Object -Property X -Maximum).Maximum
    $minY = ($face.Points | Measure-Object -Property Y -Minimum).Minimum
    $maxY = ($face.Points | Measure-Object -Property Y -Maximum).Maximum
    $width = $maxX - $minX
    $height = $maxY - $minY
    $centerX = ($face.Points | Measure-Object -Property X -Average).Average
    $centerY = ($face.Points | Measure-Object -Property Y -Average).Average

    $code = if ($materialCodes.ContainsKey($face.Material)) { $materialCodes[$face.Material] } else { '?' }
    $label = '{0}{1:D2}' -f $code, $face.Index
    $font = if ($width -ge 28 -and $height -ge 13) { $labelFont } else { $smallFont }
    $labelSize = $graphics.MeasureString($label, $font)
    $labelX = $centerX - ($labelSize.Width / 2.0)
    $labelY = $centerY - ($labelSize.Height / 2.0)
    if ($width -ge 9 -and $height -ge 5) {
        $graphics.DrawString($label, $font, $blackBrush, $labelX + 1, $labelY + 1)
        $graphics.DrawString($label, $font, $whiteBrush, $labelX, $labelY)
    }

    # Vertex order markers: 1 red, 2 green, 3 blue, 4 yellow.
    for ($vertexIndex = 0; $vertexIndex -lt $face.Points.Count; $vertexIndex++) {
        $point = $face.Points[$vertexIndex]
        $markerSize = if ($width -ge 30 -and $height -ge 20) { 6 } else { 3 }
        $graphics.FillRectangle(
            $vertexBrushes[$vertexIndex % $vertexBrushes.Count],
            $point.X - ($markerSize / 2),
            $point.Y - ($markerSize / 2),
            $markerSize,
            $markerSize
        )
        if ($width -ge 45 -and $height -ge 30) {
            $number = [string]($vertexIndex + 1)
            $graphics.DrawString($number, $smallFont, $blackBrush, $point.X + 3, $point.Y + 2)
        }
    }
}

# The two large Back faces are quarter-turned in UV space. Mark the real
# cabinet-up direction directly on the locator so directional artwork is not
# accidentally painted sideways. Model-up points toward increasing atlas X.
$orientationPen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 255, 236, 74)), 2
$orientationBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 255, 246, 128))
$orientationShadowBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(220, 0, 0, 0))
$orientationFont = New-Object System.Drawing.Font 'Arial', 7, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
foreach ($face in $faces | Where-Object { $_.Material -eq 'Back' -and $_.Index -in @(18, 21) }) {
    $minX = ($face.Points | Measure-Object -Property X -Minimum).Minimum
    $maxX = ($face.Points | Measure-Object -Property X -Maximum).Maximum
    $maxY = ($face.Points | Measure-Object -Property Y -Maximum).Maximum
    $arrowY = $maxY - 14
    $startX = $minX + 8
    $endX = $maxX - 8

    $graphics.DrawLine($orientationPen, $startX, $arrowY, $endX, $arrowY)
    $graphics.DrawLine($orientationPen, $endX, $arrowY, $endX - 7, $arrowY - 4)
    $graphics.DrawLine($orientationPen, $endX, $arrowY, $endX - 7, $arrowY + 4)
    $graphics.DrawString('MODEL UP', $orientationFont, $orientationShadowBrush, $startX + 1, $arrowY - 13)
    $graphics.DrawString('MODEL UP', $orientationFont, $orientationBrush, $startX, $arrowY - 14)
}

$bitmap.Save($outputFullPath, [System.Drawing.Imaging.ImageFormat]::Png)

$orientationFont.Dispose()
$orientationShadowBrush.Dispose()
$orientationBrush.Dispose()
$orientationPen.Dispose()
$smallFont.Dispose()
$labelFont.Dispose()
foreach ($brush in $vertexBrushes) {
    $brush.Dispose()
}
$whiteBrush.Dispose()
$blackBrush.Dispose()
$outlinePen.Dispose()
$graphics.Dispose()
$bitmap.Dispose()

Write-Output $outputFullPath
