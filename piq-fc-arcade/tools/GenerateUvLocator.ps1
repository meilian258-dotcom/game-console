param(
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\design\skins\UV_Locator_32px.png')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

function Convert-HsvToColor {
    param(
        [double]$Hue,
        [double]$Saturation,
        [double]$Value
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

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
$outputDirectory = [System.IO.Path]::GetDirectoryName($resolvedOutput)
[System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null

$bitmap = New-Object System.Drawing.Bitmap 512, 512
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None
$graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::SingleBitPerPixelGridFit

$borderPen = New-Object System.Drawing.Pen ([System.Drawing.Color]::Black), 2
$minorPen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(180, 255, 255, 255)), 1
$whiteBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
$blackBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::Black)
$labelFont = New-Object System.Drawing.Font 'Arial', 9, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)

for ($row = 0; $row -lt 16; $row++) {
    for ($column = 0; $column -lt 16; $column++) {
        $x = $column * 32
        $y = $row * 32
        $hue = (($column * 29) + ($row * 71)) % 360
        $value = if ((($column + $row) % 2) -eq 0) { 0.92 } else { 0.72 }
        $color = Convert-HsvToColor -Hue $hue -Saturation 0.78 -Value $value
        $fillBrush = New-Object System.Drawing.SolidBrush $color
        $graphics.FillRectangle($fillBrush, $x, $y, 32, 32)
        $fillBrush.Dispose()

        # 四个角使用不同标记，方便识别某个面的旋转和镜像。
        $graphics.FillRectangle($whiteBrush, $x + 2, $y + 2, 7, 7)
        $graphics.FillRectangle($blackBrush, $x + 23, $y + 23, 7, 7)
        $graphics.DrawLine($minorPen, $x + 2, $y + 29, $x + 14, $y + 17)
        $graphics.DrawLine($minorPen, $x + 29, $y + 2, $x + 17, $y + 14)

        $label = '{0}{1:X}' -f [char](65 + $column), $row
        $labelSize = $graphics.MeasureString($label, $labelFont)
        $labelX = $x + ((32 - $labelSize.Width) / 2)
        $labelY = $y + ((32 - $labelSize.Height) / 2)
        $luminance = (0.2126 * $color.R) + (0.7152 * $color.G) + (0.0722 * $color.B)
        $shadowBrush = if ($luminance -gt 145) { $whiteBrush } else { $blackBrush }
        $textBrush = if ($luminance -gt 145) { $blackBrush } else { $whiteBrush }
        $graphics.DrawString($label, $labelFont, $shadowBrush, $labelX + 1, $labelY + 1)
        $graphics.DrawString($label, $labelFont, $textBrush, $labelX, $labelY)
        $graphics.DrawRectangle($borderPen, $x, $y, 31, 31)
    }
}

$bitmap.Save($resolvedOutput, [System.Drawing.Imaging.ImageFormat]::Png)

$labelFont.Dispose()
$whiteBrush.Dispose()
$blackBrush.Dispose()
$minorPen.Dispose()
$borderPen.Dispose()
$graphics.Dispose()
$bitmap.Dispose()

Write-Output $resolvedOutput
