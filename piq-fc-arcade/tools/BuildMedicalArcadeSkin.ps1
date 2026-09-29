param(
    [string]$SourceImagePath = '',

    [string]$ObjPath = '',

    [string]$OutputPath = '',

    [ValidateSet('PillLab', 'PillLabPolished', 'DrMario', 'Racing')]
    [string]$Theme = 'PillLab',

    [string]$StyleImagePath = '',

    [string]$SidePosterImagePath = ''
)

$ErrorActionPreference = 'Stop'
$racingDisplayName = ([string][char]0x706B) + ([char]0x7BAD) + ([char]0x8F66)
Add-Type -AssemblyName System.Drawing

if ([string]::IsNullOrWhiteSpace($ObjPath)) {
    $ObjPath = Join-Path $PSScriptRoot '..\src\main\resources\assets\piq_fc_arcade\models\block\legacy_generic_machine.obj'
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $defaultName = switch ($Theme) {
        'PillLab' { 'PIQ_Pill_Lab_UV_v4.png' }
        'PillLabPolished' { 'PIQ_Pill_Lab_Reborn_UV_v2.png' }
        'Racing' { 'PIQ_Red_Racing_UV_v2.png' }
        default { 'PIQ_DrMario_Lab_UV_v3.png' }
    }
    $OutputPath = Join-Path $PSScriptRoot "..\design\skins\$defaultName"
}
if ($Theme -eq 'DrMario' -and [string]::IsNullOrWhiteSpace($SourceImagePath)) {
    throw 'SourceImagePath is required for the DrMario theme.'
}
if ($Theme -eq 'PillLabPolished' -and [string]::IsNullOrWhiteSpace($SidePosterImagePath)) {
    $SidePosterImagePath = Join-Path $PSScriptRoot '..\design\skins\PIQ_Pill_Lab_SidePoster_Concept_v1.png'
}
if ($Theme -eq 'Racing' -and [string]::IsNullOrWhiteSpace($SidePosterImagePath)) {
    $SidePosterImagePath = Join-Path $PSScriptRoot '..\design\skins\PIQ_Red_Racing_SidePoster_v1.png'
}

function New-Color {
    param([string]$Hex)
    return [System.Drawing.ColorTranslator]::FromHtml($Hex)
}

function New-MaterialPath {
    param(
        [object[]]$Faces,
        [string]$Material
    )

    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    foreach ($face in $Faces | Where-Object Material -eq $Material) {
        $path.AddPolygon([System.Drawing.PointF[]]$face.Points)
    }
    return $path
}

function New-IndexedFacePath {
    param(
        [object[]]$Faces,
        [string]$Material,
        [int[]]$Indices
    )

    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    foreach ($face in $Faces | Where-Object { $_.Material -eq $Material -and $_.Index -in $Indices }) {
        $path.AddPolygon([System.Drawing.PointF[]]$face.Points)
    }
    return $path
}

function Fill-MaterialGradient {
    param(
        [System.Drawing.Graphics]$Graphics,
        [System.Drawing.Drawing2D.GraphicsPath]$Path,
        [System.Drawing.Color]$Start,
        [System.Drawing.Color]$End,
        [float]$Angle
    )

    $state = $Graphics.Save()
    $Graphics.SetClip($Path)
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.Rectangle 0, 0, 512, 512),
        $Start,
        $End,
        $Angle
    )
    $Graphics.FillRectangle($brush, 0, 0, 512, 512)
    $brush.Dispose()
    $Graphics.Restore($state)
}

function Draw-ImageClipped {
    param(
        [System.Drawing.Graphics]$Graphics,
        [System.Drawing.Drawing2D.GraphicsPath]$ClipPath,
        [System.Drawing.Image]$Image,
        [System.Drawing.RectangleF]$Destination,
        [System.Drawing.RectangleF]$Source
    )

    $state = $Graphics.Save()
    $Graphics.SetClip($ClipPath)
    $attributes = New-Object System.Drawing.Imaging.ImageAttributes
    $attributes.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
    $Graphics.DrawImage(
        $Image,
        [System.Drawing.Rectangle]::Round($Destination),
        $Source.X,
        $Source.Y,
        $Source.Width,
        $Source.Height,
        [System.Drawing.GraphicsUnit]::Pixel,
        $attributes
    )
    $attributes.Dispose()
    $Graphics.Restore($state)
}

function Draw-SidePosterOnFace {
    param(
        [System.Drawing.Graphics]$Graphics,
        [object]$Face,
        [System.Drawing.Image]$Poster,
        [bool]$MirrorHorizontal,
        [double]$MinimumDepth = -0.019462,
        [double]$MaximumDepth = 1.000,
        [double]$MinimumHeight = 0.000,
        [double]$MaximumHeight = 1.970129
    )

    # Project a single poster through the model's real Z/Y coordinates.  This
    # keeps artwork continuous across separate UV islands. Every polygon whose
    # material is LeftSide/RightSide is part of the same orthographic side
    # silhouette, including the marquee and control-deck protrusion.
    $posterPoints = @()
    foreach ($position in $Face.Positions) {
        $depthRatio = ([double]$position[2] - $MinimumDepth) / ($MaximumDepth - $MinimumDepth)
        if ($MirrorHorizontal) {
            $depthRatio = 1.0 - $depthRatio
        }
        $heightRatio = ($MaximumHeight - [double]$position[1]) / ($MaximumHeight - $MinimumHeight)
        $posterPoints += New-Object System.Drawing.PointF(
            [float]($depthRatio * $Poster.Width),
            [float]($heightRatio * $Poster.Height)
        )
    }

    $source1 = $posterPoints[0]
    $source2 = $posterPoints[1]
    $source3 = $posterPoints[2]
    $target1 = $Face.Points[0]
    $target2 = $Face.Points[1]
    $target3 = $Face.Points[2]

    $denominator = (
        ($source1.X * ($source2.Y - $source3.Y)) +
        ($source2.X * ($source3.Y - $source1.Y)) +
        ($source3.X * ($source1.Y - $source2.Y))
    )
    if ([Math]::Abs($denominator) -lt 0.000001) {
        throw "Cannot project poster onto degenerate $($Face.Material) face $($Face.Index)."
    }

    $m11 = (
        ($target1.X * ($source2.Y - $source3.Y)) +
        ($target2.X * ($source3.Y - $source1.Y)) +
        ($target3.X * ($source1.Y - $source2.Y))
    ) / $denominator
    $m21 = (
        ($target1.X * ($source3.X - $source2.X)) +
        ($target2.X * ($source1.X - $source3.X)) +
        ($target3.X * ($source2.X - $source1.X))
    ) / $denominator
    $dx = (
        ($target1.X * (($source2.X * $source3.Y) - ($source3.X * $source2.Y))) +
        ($target2.X * (($source3.X * $source1.Y) - ($source1.X * $source3.Y))) +
        ($target3.X * (($source1.X * $source2.Y) - ($source2.X * $source1.Y)))
    ) / $denominator

    $m12 = (
        ($target1.Y * ($source2.Y - $source3.Y)) +
        ($target2.Y * ($source3.Y - $source1.Y)) +
        ($target3.Y * ($source1.Y - $source2.Y))
    ) / $denominator
    $m22 = (
        ($target1.Y * ($source3.X - $source2.X)) +
        ($target2.Y * ($source1.X - $source3.X)) +
        ($target3.Y * ($source2.X - $source1.X))
    ) / $denominator
    $dy = (
        ($target1.Y * (($source2.X * $source3.Y) - ($source3.X * $source2.Y))) +
        ($target2.Y * (($source3.X * $source1.Y) - ($source1.X * $source3.Y))) +
        ($target3.Y * (($source1.X * $source2.Y) - ($source2.X * $source1.Y)))
    ) / $denominator

    $matrix = New-Object System.Drawing.Drawing2D.Matrix(
        [float]$m11,
        [float]$m12,
        [float]$m21,
        [float]$m22,
        [float]$dx,
        [float]$dy
    )
    $clipPath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $clipPath.AddPolygon([System.Drawing.PointF[]]$posterPoints)

    $state = $Graphics.Save()
    $Graphics.Transform = $matrix
    $Graphics.SetClip($clipPath)
    $Graphics.DrawImage($Poster, 0, 0, $Poster.Width, $Poster.Height)
    $Graphics.Restore($state)

    $clipPath.Dispose()
    $matrix.Dispose()
}

function Draw-Cross {
    param(
        [System.Drawing.Graphics]$Graphics,
        [float]$CenterX,
        [float]$CenterY,
        [float]$Size,
        [System.Drawing.Brush]$Brush
    )

    $bar = $Size / 3.0
    $Graphics.FillRectangle($Brush, $CenterX - ($bar / 2.0), $CenterY - ($Size / 2.0), $bar, $Size)
    $Graphics.FillRectangle($Brush, $CenterX - ($Size / 2.0), $CenterY - ($bar / 2.0), $Size, $bar)
}

function Draw-Capsule {
    param(
        [System.Drawing.Graphics]$Graphics,
        [float]$X,
        [float]$Y,
        [float]$Width,
        [float]$Height,
        [System.Drawing.Color]$Left,
        [System.Drawing.Color]$Right,
        [float]$Angle = 0
    )

    $state = $Graphics.Save()
    $Graphics.TranslateTransform($X + ($Width / 2.0), $Y + ($Height / 2.0))
    $Graphics.RotateTransform($Angle)
    $Graphics.TranslateTransform(-($X + ($Width / 2.0)), -($Y + ($Height / 2.0)))

    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $radius = $Height / 2.0
    $path.AddArc($X, $Y, $Height, $Height, 90, 180)
    $path.AddLine($X + $radius, $Y, $X + $Width - $radius, $Y)
    $path.AddArc($X + $Width - $Height, $Y, $Height, $Height, 270, 180)
    $path.AddLine($X + $Width - $radius, $Y + $Height, $X + $radius, $Y + $Height)
    $path.CloseFigure()

    $clip = $Graphics.Save()
    $Graphics.SetClip($path)
    $leftBrush = New-Object System.Drawing.SolidBrush $Left
    $rightBrush = New-Object System.Drawing.SolidBrush $Right
    $Graphics.FillRectangle($leftBrush, $X, $Y, $Width / 2.0, $Height)
    $Graphics.FillRectangle($rightBrush, $X + ($Width / 2.0), $Y, $Width / 2.0, $Height)
    $leftBrush.Dispose()
    $rightBrush.Dispose()
    $Graphics.Restore($clip)

    $outline = New-Object System.Drawing.Pen (New-Color '#102033'), ([Math]::Max(1.2, $Height / 12.0))
    $Graphics.DrawPath($outline, $path)
    $Graphics.DrawLine($outline, $X + ($Width / 2.0), $Y + 1, $X + ($Width / 2.0), $Y + $Height - 1)
    $outline.Dispose()
    $path.Dispose()
    $Graphics.Restore($state)
}

function Draw-Germ {
    param(
        [System.Drawing.Graphics]$Graphics,
        [float]$CenterX,
        [float]$CenterY,
        [float]$Size,
        [System.Drawing.Color]$Color
    )

    $outlineColor = New-Color '#111A2A'
    $bodyBrush = New-Object System.Drawing.SolidBrush $Color
    $outlinePen = New-Object System.Drawing.Pen $outlineColor, ([Math]::Max(1.0, $Size / 11.0))
    $whiteBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
    $darkBrush = New-Object System.Drawing.SolidBrush $outlineColor

    $spikeSize = $Size * 0.19
    $spikeRadius = $Size * 0.46
    for ($index = 0; $index -lt 8; $index++) {
        $angle = ($index * [Math]::PI) / 4.0
        $spikeX = $CenterX + ([Math]::Cos($angle) * $spikeRadius) - ($spikeSize / 2.0)
        $spikeY = $CenterY + ([Math]::Sin($angle) * $spikeRadius) - ($spikeSize / 2.0)
        $Graphics.FillEllipse($bodyBrush, $spikeX, $spikeY, $spikeSize, $spikeSize)
        $Graphics.DrawEllipse($outlinePen, $spikeX, $spikeY, $spikeSize, $spikeSize)
    }

    $bodySize = $Size * 0.78
    $bodyX = $CenterX - ($bodySize / 2.0)
    $bodyY = $CenterY - ($bodySize / 2.0)
    $Graphics.FillEllipse($bodyBrush, $bodyX, $bodyY, $bodySize, $bodySize)
    $Graphics.DrawEllipse($outlinePen, $bodyX, $bodyY, $bodySize, $bodySize)

    $eyeSize = $Size * 0.13
    $eyeY = $CenterY - ($Size * 0.13)
    foreach ($eyeX in @(($CenterX - ($Size * 0.18)), ($CenterX + ($Size * 0.05)))) {
        $Graphics.FillEllipse($whiteBrush, $eyeX, $eyeY, $eyeSize, $eyeSize)
        $Graphics.FillEllipse($darkBrush, $eyeX + ($eyeSize * 0.35), $eyeY + ($eyeSize * 0.35), $eyeSize * 0.42, $eyeSize * 0.42)
    }
    $Graphics.FillRectangle($darkBrush, $CenterX - ($Size * 0.18), $CenterY + ($Size * 0.10), $Size * 0.36, $Size * 0.10)

    $bodyBrush.Dispose()
    $outlinePen.Dispose()
    $whiteBrush.Dispose()
    $darkBrush.Dispose()
}

function Draw-Ecg {
    param(
        [System.Drawing.Graphics]$Graphics,
        [float]$X,
        [float]$Y,
        [float]$Width,
        [System.Drawing.Color]$Color
    )

    $pen = New-Object System.Drawing.Pen $Color, 1.5
    $points = [System.Drawing.PointF[]]@(
        (New-Object System.Drawing.PointF $X, $Y),
        (New-Object System.Drawing.PointF ($X + ($Width * 0.22)), $Y),
        (New-Object System.Drawing.PointF ($X + ($Width * 0.33)), ($Y - 7)),
        (New-Object System.Drawing.PointF ($X + ($Width * 0.43)), ($Y + 8)),
        (New-Object System.Drawing.PointF ($X + ($Width * 0.55)), ($Y - 14)),
        (New-Object System.Drawing.PointF ($X + ($Width * 0.66)), ($Y + 4)),
        (New-Object System.Drawing.PointF ($X + ($Width * 0.76)), $Y),
        (New-Object System.Drawing.PointF ($X + $Width), $Y)
    )
    $Graphics.DrawLines($pen, $points)
    $pen.Dispose()
}

function Draw-Hexagon {
    param(
        [System.Drawing.Graphics]$Graphics,
        [float]$CenterX,
        [float]$CenterY,
        [float]$Radius,
        [System.Drawing.Color]$Color
    )

    $points = @()
    for ($index = 0; $index -lt 6; $index++) {
        $angle = ((60 * $index) - 30) * [Math]::PI / 180.0
        $points += New-Object System.Drawing.PointF(
            [float]($CenterX + ([Math]::Cos($angle) * $Radius)),
            [float]($CenterY + ([Math]::Sin($angle) * $Radius))
        )
    }
    $pen = New-Object System.Drawing.Pen $Color, 1.2
    $Graphics.DrawPolygon($pen, [System.Drawing.PointF[]]$points)
    $pen.Dispose()
}

function Draw-PanelTexture {
    param(
        [System.Drawing.Graphics]$Graphics,
        [System.Drawing.Drawing2D.GraphicsPath]$Path,
        [float]$X,
        [float]$Y,
        [float]$Width,
        [float]$Height,
        [ValidateSet('Brushed', 'Honeycomb', 'Speckle')]
        [string]$Mode,
        [System.Drawing.Color]$Color
    )

    $state = $Graphics.Save()
    $Graphics.SetClip($Path)
    $softColor = [System.Drawing.Color]::FromArgb(44, $Color.R, $Color.G, $Color.B)
    $faintColor = [System.Drawing.Color]::FromArgb(24, $Color.R, $Color.G, $Color.B)
    $softPen = New-Object System.Drawing.Pen $softColor, 0.8
    $faintPen = New-Object System.Drawing.Pen $faintColor, 0.7
    $softBrush = New-Object System.Drawing.SolidBrush $softColor

    if ($Mode -eq 'Brushed') {
        for ($lineY = $Y + 2; $lineY -lt ($Y + $Height); $lineY += 4) {
            $offset = ([int](($lineY - $Y) / 4) % 3) * 5
            $Graphics.DrawLine($faintPen, $X + 2 + $offset, $lineY, $X + $Width - 3, $lineY)
        }
        for ($index = 0; $index -lt 26; $index++) {
            $grainX = $X + 3 + (($index * 37) % [Math]::Max(4, [int]($Width - 7)))
            $grainY = $Y + 2 + (($index * 19) % [Math]::Max(4, [int]($Height - 5)))
            $Graphics.DrawLine($softPen, $grainX, $grainY, $grainX + 5 + ($index % 7), $grainY)
        }
    } elseif ($Mode -eq 'Honeycomb') {
        $radius = 5.0
        $row = 0
        for ($hexY = $Y + 5; $hexY -lt ($Y + $Height); $hexY += 8.5) {
            $startX = $X + 5 + (($row % 2) * 7.5)
            for ($hexX = $startX; $hexX -lt ($X + $Width); $hexX += 15) {
                Draw-Hexagon $Graphics $hexX $hexY $radius $softColor
            }
            $row++
        }
    } else {
        for ($index = 0; $index -lt 52; $index++) {
            $dotX = $X + 2 + (($index * 29) % [Math]::Max(3, [int]($Width - 4)))
            $dotY = $Y + 2 + (($index * 43) % [Math]::Max(3, [int]($Height - 4)))
            $dotSize = if (($index % 5) -eq 0) { 1.4 } else { 0.8 }
            $Graphics.FillEllipse($softBrush, $dotX, $dotY, $dotSize, $dotSize)
        }
    }

    $softBrush.Dispose()
    $faintPen.Dispose()
    $softPen.Dispose()
    $Graphics.Restore($state)
}

function Draw-PanelRivets {
    param(
        [System.Drawing.Graphics]$Graphics,
        [System.Drawing.Drawing2D.GraphicsPath]$Path,
        [float]$X,
        [float]$Y,
        [float]$Width,
        [float]$Height
    )

    $state = $Graphics.Save()
    $Graphics.SetClip($Path)
    $shadow = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(135, 12, 35, 55))
    $highlight = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(190, 218, 255, 255))
    foreach ($point in @(
        @([float]($X + 4), [float]($Y + 4)),
        @([float]($X + $Width - 6), [float]($Y + 4)),
        @([float]($X + 4), [float]($Y + $Height - 6)),
        @([float]($X + $Width - 6), [float]($Y + $Height - 6))
    )) {
        $Graphics.FillEllipse($shadow, $point[0], $point[1], 3.2, 3.2)
        $Graphics.FillEllipse($highlight, $point[0] + 0.6, $point[1] + 0.5, 1.1, 1.1)
    }
    $highlight.Dispose()
    $shadow.Dispose()
    $Graphics.Restore($state)
}

function Draw-CheckeredPattern {
    param(
        [System.Drawing.Graphics]$Graphics,
        [System.Drawing.Drawing2D.GraphicsPath]$Path,
        [float]$X,
        [float]$Y,
        [float]$Width,
        [float]$Height,
        [float]$CellSize,
        [System.Drawing.Color]$First,
        [System.Drawing.Color]$Second
    )

    $state = $Graphics.Save()
    $Graphics.SetClip($Path)
    $firstBrush = New-Object System.Drawing.SolidBrush $First
    $secondBrush = New-Object System.Drawing.SolidBrush $Second
    $row = 0
    for ($cellY = $Y; $cellY -lt ($Y + $Height); $cellY += $CellSize) {
        $column = 0
        for ($cellX = $X; $cellX -lt ($X + $Width); $cellX += $CellSize) {
            $brush = if ((($row + $column) % 2) -eq 0) { $firstBrush } else { $secondBrush }
            $Graphics.FillRectangle($brush, $cellX, $cellY, $CellSize + 0.5, $CellSize + 0.5)
            $column++
        }
        $row++
    }
    $secondBrush.Dispose()
    $firstBrush.Dispose()
    $Graphics.Restore($state)
}

function Draw-RacingSpeedLines {
    param(
        [System.Drawing.Graphics]$Graphics,
        [System.Drawing.Drawing2D.GraphicsPath]$Path,
        [float]$X,
        [float]$Y,
        [float]$Width,
        [System.Drawing.Color]$Primary,
        [System.Drawing.Color]$Secondary
    )

    $state = $Graphics.Save()
    $Graphics.SetClip($Path)
    $primaryPen = New-Object System.Drawing.Pen $Primary, 3.0
    $secondaryPen = New-Object System.Drawing.Pen $Secondary, 1.4
    $primaryPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $primaryPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $secondaryPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $secondaryPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $Graphics.DrawLine($primaryPen, $X, $Y, $X + ($Width * 0.72), $Y)
    $Graphics.DrawLine($primaryPen, $X + ($Width * 0.18), $Y + 7, $X + $Width, $Y + 7)
    $Graphics.DrawLine($secondaryPen, $X + ($Width * 0.05), $Y + 13, $X + ($Width * 0.58), $Y + 13)
    $Graphics.DrawLine($secondaryPen, $X + ($Width * 0.66), $Y + 13, $X + ($Width * 0.93), $Y + 13)
    $secondaryPen.Dispose()
    $primaryPen.Dispose()
    $Graphics.Restore($state)
}

function Draw-Tachometer {
    param(
        [System.Drawing.Graphics]$Graphics,
        [float]$CenterX,
        [float]$CenterY,
        [float]$Radius,
        [System.Drawing.Color]$DialColor,
        [System.Drawing.Color]$NeedleColor
    )

    $dialPen = New-Object System.Drawing.Pen $DialColor, ([Math]::Max(1.2, $Radius / 12.0))
    $needlePen = New-Object System.Drawing.Pen $NeedleColor, ([Math]::Max(1.4, $Radius / 10.0))
    $Graphics.DrawArc($dialPen, $CenterX - $Radius, $CenterY - $Radius, $Radius * 2, $Radius * 2, 195, 150)
    for ($index = 0; $index -le 8; $index++) {
        $angle = (195 + ($index * 18.75)) * [Math]::PI / 180.0
        $outerX = $CenterX + ([Math]::Cos($angle) * $Radius)
        $outerY = $CenterY + ([Math]::Sin($angle) * $Radius)
        $innerX = $CenterX + ([Math]::Cos($angle) * ($Radius * 0.78))
        $innerY = $CenterY + ([Math]::Sin($angle) * ($Radius * 0.78))
        $Graphics.DrawLine($dialPen, $innerX, $innerY, $outerX, $outerY)
    }
    $needleAngle = 305 * [Math]::PI / 180.0
    $Graphics.DrawLine(
        $needlePen,
        $CenterX,
        $CenterY,
        $CenterX + ([Math]::Cos($needleAngle) * ($Radius * 0.73)),
        $CenterY + ([Math]::Sin($needleAngle) * ($Radius * 0.73))
    )
    $hub = New-Object System.Drawing.SolidBrush $NeedleColor
    $Graphics.FillEllipse($hub, $CenterX - 2.5, $CenterY - 2.5, 5, 5)
    $hub.Dispose()
    $needlePen.Dispose()
    $dialPen.Dispose()
}

$objFullPath = [System.IO.Path]::GetFullPath($ObjPath)
$sourceFullPath = if ([string]::IsNullOrWhiteSpace($SourceImagePath)) { $null } else { [System.IO.Path]::GetFullPath($SourceImagePath) }
$sidePosterFullPath = if ([string]::IsNullOrWhiteSpace($SidePosterImagePath)) { $null } else { [System.IO.Path]::GetFullPath($SidePosterImagePath) }
$outputFullPath = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($outputFullPath)) | Out-Null

$vertices = @()
$textureCoordinates = @()
$faces = @()
$currentMaterial = ''
$materialFaceIndex = @{}

foreach ($line in [System.IO.File]::ReadLines($objFullPath)) {
    if ($line.StartsWith('v ')) {
        $parts = $line.Split(' ', [System.StringSplitOptions]::RemoveEmptyEntries)
        $vertices += ,@(
            [double]::Parse($parts[1], [Globalization.CultureInfo]::InvariantCulture),
            [double]::Parse($parts[2], [Globalization.CultureInfo]::InvariantCulture),
            [double]::Parse($parts[3], [Globalization.CultureInfo]::InvariantCulture)
        )
    } elseif ($line.StartsWith('vt ')) {
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
        $positions = @()
        foreach ($token in $line.Substring(2).Split(' ', [System.StringSplitOptions]::RemoveEmptyEntries)) {
            $indices = $token.Split('/')
            $position = $vertices[[int]$indices[0] - 1]
            $uv = $textureCoordinates[[int]$indices[1] - 1]
            $points += New-Object System.Drawing.PointF ([float]($uv[0] * 512.0)), ([float]((1.0 - $uv[1]) * 512.0))
            $positions += ,$position
        }
        $faces += [pscustomobject]@{
            Material = $currentMaterial
            Index = $materialFaceIndex[$currentMaterial]
            Points = $points
            Positions = $positions
        }
    }
}

$paths = @{}
foreach ($material in @('Screen', 'ScreenBorder', 'RightSide', 'LeftSide', 'Controller', 'Front', 'Top', 'Back', 'Bottom', 'Bezel', 'Coin')) {
    $paths[$material] = New-MaterialPath -Faces $faces -Material $material
}

$bitmap = New-Object System.Drawing.Bitmap 512, 512
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.Clear([System.Drawing.Color]::Black)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

Fill-MaterialGradient $graphics $paths['RightSide'] (New-Color '#09BBD4') (New-Color '#82ECF2') 90
Fill-MaterialGradient $graphics $paths['LeftSide'] (New-Color '#F7FCFF') (New-Color '#AEEEF2') 90
Fill-MaterialGradient $graphics $paths['Controller'] (New-Color '#E8FBFF') (New-Color '#36CADA') 0
Fill-MaterialGradient $graphics $paths['Front'] (New-Color '#0CB8D2') (New-Color '#F6FCFF') 25
Fill-MaterialGradient $graphics $paths['Top'] (New-Color '#1439C9') (New-Color '#F1273D') 0
Fill-MaterialGradient $graphics $paths['Back'] (New-Color '#078AA9') (New-Color '#18D2D8') 90
Fill-MaterialGradient $graphics $paths['Bottom'] (New-Color '#B80D2F') (New-Color '#F23B41') 0
Fill-MaterialGradient $graphics $paths['Bezel'] (New-Color '#12192A') (New-Color '#3A173D') 90
Fill-MaterialGradient $graphics $paths['ScreenBorder'] (New-Color '#A20D32') (New-Color '#EF2448') 45
Fill-MaterialGradient $graphics $paths['Screen'] (New-Color '#071019') (New-Color '#102D39') 90
Fill-MaterialGradient $graphics $paths['Coin'] (New-Color '#12151C') (New-Color '#38404A') 90

# Keep the supplied art inside the broad side panels instead of stretching one poster
# across the marquee, control-deck protrusion and lower cabinet at once.
$rightUpperPanel = New-IndexedFacePath -Faces $faces -Material 'RightSide' -Indices @(7)
$rightLowerPanel = New-IndexedFacePath -Faces $faces -Material 'RightSide' -Indices @(4)
$leftUpperPanel = New-IndexedFacePath -Faces $faces -Material 'LeftSide' -Indices @(9)
$leftLowerPanel = New-IndexedFacePath -Faces $faces -Material 'LeftSide' -Indices @(12)
$rightMarqueeSide = New-IndexedFacePath -Faces $faces -Material 'RightSide' -Indices @(1)
$leftMarqueeSide = New-IndexedFacePath -Faces $faces -Material 'LeftSide' -Indices @(15)
$controllerTop = New-IndexedFacePath -Faces $faces -Material 'Controller' -Indices @(1)
$backLeftPanel = New-IndexedFacePath -Faces $faces -Material 'Back' -Indices @(21)
$backRightPanel = New-IndexedFacePath -Faces $faces -Material 'Back' -Indices @(18)
$frontLowerPanel = New-IndexedFacePath -Faces $faces -Material 'Front' -Indices @(1)
$topRoofPanel = New-IndexedFacePath -Faces $faces -Material 'Top' -Indices @(1)
$controllerFrontPanel = New-IndexedFacePath -Faces $faces -Material 'Controller' -Indices @(2)
$controllerRearTrim = New-IndexedFacePath -Faces $faces -Material 'Controller' -Indices @(3)
$frontServicePanel = New-IndexedFacePath -Faces $faces -Material 'Front' -Indices @(2)
$topSecondaryPanel = New-IndexedFacePath -Faces $faces -Material 'Top' -Indices @(2)
$rightShoulderPanel = New-IndexedFacePath -Faces $faces -Material 'RightSide' -Indices @(2)
$leftShoulderPanel = New-IndexedFacePath -Faces $faces -Material 'LeftSide' -Indices @(14)
$rightShoulderTrim = New-IndexedFacePath -Faces $faces -Material 'RightSide' -Indices @(5)
$leftShoulderTrim = New-IndexedFacePath -Faces $faces -Material 'LeftSide' -Indices @(11)

$source = $null
if ($Theme -eq 'DrMario') {
    $source = [System.Drawing.Image]::FromFile($sourceFullPath)

    $doctorHeadSource = New-Object System.Drawing.RectangleF(
        [float]($source.Width * 0.31),
        [float]($source.Height * 0.40),
        [float]($source.Width * 0.69),
        [float]($source.Height * 0.44)
    )
    $doctorHeadDestination = New-Object System.Drawing.RectangleF 36, 282, 82, 98
    Draw-ImageClipped $graphics $rightUpperPanel $source $doctorHeadDestination $doctorHeadSource

    $doctorCoatSource = New-Object System.Drawing.RectangleF(
        [float]($source.Width * 0.27),
        [float]($source.Height * 0.69),
        [float]($source.Width * 0.70),
        [float]($source.Height * 0.31)
    )
    $doctorCoatDestination = New-Object System.Drawing.RectangleF 39, 414, 92, 92
    Draw-ImageClipped $graphics $rightLowerPanel $source $doctorCoatDestination $doctorCoatSource

    $virusSource = New-Object System.Drawing.RectangleF(
        0,
        [float]($source.Height * 0.32),
        [float]($source.Width * 0.58),
        [float]($source.Height * 0.28)
    )
    $virusDestination = New-Object System.Drawing.RectangleF 139, 414, 91, 92
    Draw-ImageClipped $graphics $leftLowerPanel $source $virusDestination $virusSource
}

if ($Theme -eq 'PillLabPolished') {
    # Reborn is a complete original redraw. The former StyleImagePath parameter
    # remains only for command-line compatibility and is deliberately never read.
    # Seed every structural material with a restrained finish first; the detailed
    # panels below then receive their own stronger surface treatment.
    Draw-PanelTexture $graphics $paths['Bottom'] 0 0 512 512 'Speckle' (New-Color '#FFE5EB')
    Draw-PanelTexture $graphics $paths['Top'] 0 0 512 512 'Speckle' (New-Color '#D7F9FF')
    Draw-PanelTexture $graphics $paths['Front'] 0 0 512 512 'Speckle' (New-Color '#0A7794')
    Draw-PanelTexture $graphics $paths['Back'] 0 0 512 512 'Speckle' (New-Color '#D7FFFF')
    Draw-PanelTexture $graphics $paths['Controller'] 0 0 512 512 'Speckle' (New-Color '#0A7794')

    $freshWhite = New-Object System.Drawing.SolidBrush (New-Color '#F4FDFF')
    $freshNavy = New-Object System.Drawing.SolidBrush (New-Color '#101B35')
    $freshRed = New-Object System.Drawing.SolidBrush (New-Color '#F22B48')
    $freshBlue = New-Object System.Drawing.SolidBrush (New-Color '#1851D8')
    $freshYellow = New-Object System.Drawing.SolidBrush (New-Color '#FFD43B')
    $freshCyan = New-Object System.Drawing.SolidBrush (New-Color '#4DE3E7')
    $freshDarkCyan = New-Object System.Drawing.SolidBrush (New-Color '#087E9C')
    $freshLine = New-Object System.Drawing.Pen (New-Color '#102441'), 1.25
    $freshLightLine = New-Object System.Drawing.Pen (New-Color '#BDFBFC'), 1.15

    # Complete control console: every pixel is painted before controls are added,
    # preventing the old black triangular gaps at the shoulders.
    Fill-MaterialGradient $graphics $controllerTop (New-Color '#087FA8') (New-Color '#53E3E8') 0
    Draw-PanelTexture $graphics $controllerTop 0 20 113 29 'Speckle' (New-Color '#E2FFFF')
    Draw-PanelRivets $graphics $controllerTop 0 20 113 29
    $controllerTopState = $graphics.Save()
    $graphics.SetClip($controllerTop)
    $graphics.FillEllipse($freshNavy, 10, 28, 20, 11)
    $graphics.DrawEllipse($freshLightLine, 10, 28, 20, 11)
    $graphics.DrawLine($freshLine, 20, 31, 20, 21)
    $graphics.FillEllipse($freshRed, 15, 17, 11, 11)
    $graphics.DrawEllipse($freshWhite, 16, 18, 8, 8)
    $graphics.FillEllipse($freshRed, 51, 27, 13, 10)
    $graphics.FillEllipse($freshBlue, 68, 27, 13, 10)
    $graphics.FillEllipse($freshYellow, 85, 27, 13, 10)
    Draw-Cross $graphics 103 31 10 $freshWhite
    Draw-Ecg $graphics 38 43 59 (New-Color '#D6FFFF')
    $graphics.Restore($controllerTopState)

    # Front fascia and service doors are newly drawn as one medical console family.
    Fill-MaterialGradient $graphics $controllerFrontPanel (New-Color '#F4FDFF') (New-Color '#66D6E4') 0
    Draw-PanelTexture $graphics $controllerFrontPanel 233 81 112 55 'Brushed' (New-Color '#087A9A')
    Draw-PanelRivets $graphics $controllerFrontPanel 233 81 112 55
    $controllerFrontState = $graphics.Save()
    $graphics.SetClip($controllerFrontPanel)
    $graphics.FillRectangle($freshDarkCyan, 233, 81, 9, 55)
    Draw-Ecg $graphics 244 105 88 (New-Color '#159BB4')
    Draw-Hexagon $graphics 328 109 9 (New-Color '#0B8FAB')
    $graphics.Restore($controllerFrontState)

    Fill-MaterialGradient $graphics $controllerRearTrim (New-Color '#10355E') (New-Color '#1ED4DE') 0
    Draw-PanelTexture $graphics $controllerRearTrim 113 17 112 26 'Brushed' (New-Color '#E2FFFF')
    $rearTrimState = $graphics.Save()
    $graphics.SetClip($controllerRearTrim)
    Draw-Ecg $graphics 120 31 96 (New-Color '#D9FFFF')
    $graphics.Restore($rearTrimState)

    Fill-MaterialGradient $graphics $frontLowerPanel (New-Color '#ECFCFF') (New-Color '#39C7D8') 90
    Draw-PanelTexture $graphics $frontLowerPanel 0 49 113 86 'Honeycomb' (New-Color '#0C91A9')
    Draw-PanelRivets $graphics $frontLowerPanel 0 49 113 86
    $frontLowerState = $graphics.Save()
    $graphics.SetClip($frontLowerPanel)
    Draw-Hexagon $graphics 56 88 23 (New-Color '#078BA8')
    Draw-Cross $graphics 56 88 23 $freshWhite
    Draw-Ecg $graphics 14 119 84 (New-Color '#087F9E')
    foreach ($ventY in @(126, 130)) {
        $graphics.DrawLine($freshLine, 78, $ventY, 101, $ventY)
    }
    $graphics.Restore($frontLowerState)

    Fill-MaterialGradient $graphics $frontServicePanel (New-Color '#0A90B0') (New-Color '#55E6E8') 90
    Draw-PanelTexture $graphics $frontServicePanel 268 268 112 62 'Speckle' (New-Color '#E7FFFF')
    Draw-PanelRivets $graphics $frontServicePanel 268 268 112 62
    $frontServiceState = $graphics.Save()
    $graphics.SetClip($frontServicePanel)
    Draw-Hexagon $graphics 298 296 18 (New-Color '#C9FFFF')
    Draw-Cross $graphics 298 296 18 $freshWhite
    Draw-Capsule $graphics 325 286 39 14 (New-Color '#F22B48') (New-Color '#F7FFFF') 15
    foreach ($ventY in @(313, 318, 323)) {
        $graphics.DrawLine($freshLine, 329, $ventY, 365, $ventY)
    }
    $graphics.Restore($frontServiceState)

    # Entire roof uses a new molecular circuit motif.
    Fill-MaterialGradient $graphics $topRoofPanel (New-Color '#0A9DBD') (New-Color '#153BBD') 20
    Draw-PanelTexture $graphics $topRoofPanel 268 331 112 91 'Honeycomb' (New-Color '#7DF7F1')
    Draw-PanelRivets $graphics $topRoofPanel 268 331 112 91
    $topRoofState = $graphics.Save()
    $graphics.SetClip($topRoofPanel)
    Draw-Hexagon $graphics 292 352 13 (New-Color '#BDFBFC')
    Draw-Hexagon $graphics 337 386 17 (New-Color '#56E4E8')
    Draw-Capsule $graphics 304 374 55 18 (New-Color '#F22B48') (New-Color '#F7FFFF') -12
    $graphics.DrawLine($freshLightLine, 275, 407, 369, 341)
    $graphics.Restore($topRoofState)

    Fill-MaterialGradient $graphics $topSecondaryPanel (New-Color '#173DCC') (New-Color '#E62B50') 0
    Draw-PanelTexture $graphics $topSecondaryPanel 346 90 112 45 'Brushed' (New-Color '#FFFFFF')
    Draw-PanelRivets $graphics $topSecondaryPanel 346 90 112 45
    $topSecondaryState = $graphics.Save()
    $graphics.SetClip($topSecondaryPanel)
    Draw-Capsule $graphics 375 104 52 17 (New-Color '#1851D8') (New-Color '#F7FFFF') 0
    Draw-Ecg $graphics 353 127 92 (New-Color '#FFEAF0')
    $graphics.Restore($topSecondaryState)

    # Back is designed from scratch as two complementary equipment bays.
    # B21 and B18 are quarter-turned in UV space: model-up points toward atlas
    # +X. Keep their decorations rotation-safe or draw physical horizontal
    # details as vertical strokes in the atlas.
    Fill-MaterialGradient $graphics $backLeftPanel (New-Color '#0C769B') (New-Color '#13BFD0') 90
    Draw-PanelTexture $graphics $backLeftPanel 13 139 82 112 'Honeycomb' (New-Color '#BBFFFF')
    Draw-PanelRivets $graphics $backLeftPanel 13 139 82 112
    $backLeftState = $graphics.Save()
    $graphics.SetClip($backLeftPanel)
    foreach ($equipmentRailX in @(36, 56, 76)) {
        $graphics.DrawLine($freshLightLine, $equipmentRailX, 158, $equipmentRailX, 235)
    }
    $graphics.FillEllipse($freshBlue, 31, 186, 10, 10)
    $graphics.FillEllipse($freshRed, 51, 186, 10, 10)
    $graphics.FillEllipse($freshYellow, 71, 186, 10, 10)
    $graphics.Restore($backLeftState)

    Fill-MaterialGradient $graphics $backRightPanel (New-Color '#F4FDFF') (New-Color '#74E2E8') 90
    Draw-PanelTexture $graphics $backRightPanel 137 139 89 112 'Brushed' (New-Color '#0B87A1')
    Draw-PanelRivets $graphics $backRightPanel 137 139 89 112
    $backRightState = $graphics.Save()
    $graphics.SetClip($backRightPanel)
    Draw-Hexagon $graphics 179 181 25 (New-Color '#0797B0')
    Draw-Cross $graphics 179 181 25 $freshWhite
    foreach ($ventX in @(159, 166, 193, 200)) {
        $graphics.DrawLine($freshLine, $ventX, 211, $ventX, 243)
    }
    $graphics.Restore($backRightState)

    # Side geometry receives one continuous original poster in model space.
    # It intentionally covers every side face, including marquee and shoulders.

    if (-not [System.IO.File]::Exists($sidePosterFullPath)) {
        throw "Side poster artwork does not exist: $sidePosterFullPath"
    }

    # Scale one newly generated, full-height illustration onto a compact
    # working poster. No regions from the older style atlas participate in the
    # side artwork.
    $sidePosterSource = [System.Drawing.Image]::FromFile($sidePosterFullPath)
    $sidePoster = New-Object System.Drawing.Bitmap 268, 514
    $sidePosterGraphics = [System.Drawing.Graphics]::FromImage($sidePoster)
    $sidePosterGraphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $sidePosterGraphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $sidePosterGraphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $sidePosterGraphics.DrawImage(
        $sidePosterSource,
        (New-Object System.Drawing.Rectangle 0, 0, 268, 514),
        0, 0, $sidePosterSource.Width, $sidePosterSource.Height,
        [System.Drawing.GraphicsUnit]::Pixel
    )

    # Add exact title text deterministically after image generation.
    $titleFormat = New-Object System.Drawing.StringFormat
    $titleFormat.Alignment = [System.Drawing.StringAlignment]::Center
    $titleFormat.LineAlignment = [System.Drawing.StringAlignment]::Center
    $titleFont = New-Object System.Drawing.Font 'Arial Black', 17, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $titleShadow = New-Object System.Drawing.SolidBrush (New-Color '#10233D')
    $titleGold = New-Object System.Drawing.SolidBrush (New-Color '#FFD735')

    $sidePosterGraphics.DrawString(
        'PIQ PILL LAB',
        $titleFont,
        $titleShadow,
        (New-Object System.Drawing.RectangleF 47, 27, 178, 27),
        $titleFormat
    )
    $sidePosterGraphics.DrawString(
        'PIQ PILL LAB',
        $titleFont,
        $titleGold,
        (New-Object System.Drawing.RectangleF 46, 26, 178, 27),
        $titleFormat
    )

    foreach ($face in $faces | Where-Object { $_.Material -eq 'RightSide' }) {
        Draw-SidePosterOnFace $graphics $face $sidePoster $false
    }
    foreach ($face in $faces | Where-Object { $_.Material -eq 'LeftSide' }) {
        Draw-SidePosterOnFace $graphics $face $sidePoster $true
    }

    $titleGold.Dispose()
    $titleShadow.Dispose()
    $titleFont.Dispose()
    $titleFormat.Dispose()
    $sidePosterGraphics.Dispose()
    $sidePoster.Dispose()
    $sidePosterSource.Dispose()

    $freshLightLine.Dispose()
    $freshLine.Dispose()
    $freshDarkCyan.Dispose()
    $freshCyan.Dispose()
    $freshYellow.Dispose()
    $freshBlue.Dispose()
    $freshRed.Dispose()
    $freshNavy.Dispose()
    $freshWhite.Dispose()
}

if ($Theme -eq 'Racing') {
    # Racing is a separate visual system: lacquer red, graphite, white track
    # markings and restrained amber instrument lighting. All structural faces
    # are repainted before the continuous side artwork is projected.
    Fill-MaterialGradient $graphics $paths['RightSide'] (New-Color '#38050A') (New-Color '#E51622') 25
    Fill-MaterialGradient $graphics $paths['LeftSide'] (New-Color '#140F12') (New-Color '#C90D19') 155
    Fill-MaterialGradient $graphics $paths['Controller'] (New-Color '#131519') (New-Color '#B70B15') 25
    Fill-MaterialGradient $graphics $paths['Front'] (New-Color '#16191E') (New-Color '#D81722') 90
    Fill-MaterialGradient $graphics $paths['Top'] (New-Color '#0B0D10') (New-Color '#8D0710') 0
    Fill-MaterialGradient $graphics $paths['Back'] (New-Color '#111419') (New-Color '#4B1015') 90
    Fill-MaterialGradient $graphics $paths['Bottom'] (New-Color '#8D0710') (New-Color '#E61A24') 0
    Fill-MaterialGradient $graphics $paths['Bezel'] (New-Color '#07080A') (New-Color '#231117') 90
    Fill-MaterialGradient $graphics $paths['ScreenBorder'] (New-Color '#48030A') (New-Color '#F21C28') 45
    Fill-MaterialGradient $graphics $paths['Screen'] (New-Color '#010203') (New-Color '#090B0E') 90
    Fill-MaterialGradient $graphics $paths['Coin'] (New-Color '#171A20') (New-Color '#3B414A') 90

    Draw-PanelTexture $graphics $paths['Controller'] 0 0 512 512 'Speckle' (New-Color '#FFD6D8')
    Draw-PanelTexture $graphics $paths['Front'] 0 0 512 512 'Brushed' (New-Color '#FF6A70')
    Draw-PanelTexture $graphics $paths['Top'] 0 0 512 512 'Speckle' (New-Color '#F63B44')
    Draw-PanelTexture $graphics $paths['Back'] 0 0 512 512 'Brushed' (New-Color '#8E959F')
    Draw-PanelTexture $graphics $paths['Bottom'] 0 0 512 512 'Speckle' (New-Color '#FFD6D8')

    $raceWhite = New-Object System.Drawing.SolidBrush (New-Color '#F7F7F2')
    $raceBlack = New-Object System.Drawing.SolidBrush (New-Color '#0A0B0E')
    $raceRed = New-Object System.Drawing.SolidBrush (New-Color '#ED1723')
    $raceDarkRed = New-Object System.Drawing.SolidBrush (New-Color '#7A050D')
    $raceAmber = New-Object System.Drawing.SolidBrush (New-Color '#FFB229')
    $raceSilver = New-Object System.Drawing.SolidBrush (New-Color '#BFC6CE')
    $raceOutline = New-Object System.Drawing.Pen (New-Color '#050608'), 1.5
    $raceWhitePen = New-Object System.Drawing.Pen (New-Color '#F7F7F2'), 1.3
    $raceRedPen = New-Object System.Drawing.Pen (New-Color '#ED1723'), 1.6

    # Control deck: carbon-like checker strip, joystick and four racing buttons.
    Fill-MaterialGradient $graphics $controllerTop (New-Color '#101216') (New-Color '#A70812') 0
    Draw-CheckeredPattern $graphics $controllerTop 0 20 113 29 7 (New-Color '#15181D') (New-Color '#2B2F36')
    $controllerTopState = $graphics.Save()
    $graphics.SetClip($controllerTop)
    $graphics.FillEllipse($raceBlack, 8, 27, 23, 12)
    $graphics.DrawEllipse($raceWhitePen, 8, 27, 23, 12)
    $graphics.DrawLine($raceOutline, 19.5, 31, 19.5, 20)
    $graphics.FillEllipse($raceRed, 13, 16, 13, 13)
    $graphics.DrawEllipse($raceWhitePen, 14, 17, 10, 10)
    $graphics.FillEllipse($raceRed, 48, 27, 13, 10)
    $graphics.FillEllipse($raceWhite, 64, 27, 13, 10)
    $graphics.FillEllipse($raceAmber, 80, 27, 13, 10)
    $graphics.FillEllipse($raceRed, 96, 27, 13, 10)
    $graphics.Restore($controllerTopState)
    Draw-PanelRivets $graphics $controllerTop 0 20 113 29

    Fill-MaterialGradient $graphics $controllerFrontPanel (New-Color '#121419') (New-Color '#8F0710') 0
    Draw-RacingSpeedLines $graphics $controllerFrontPanel 240 102 95 (New-Color '#F7F7F2') (New-Color '#ED1723')
    Draw-PanelRivets $graphics $controllerFrontPanel 233 81 112 55

    Fill-MaterialGradient $graphics $controllerRearTrim (New-Color '#E81924') (New-Color '#56050B') 0
    Draw-RacingSpeedLines $graphics $controllerRearTrim 118 23 99 (New-Color '#F7F7F2') (New-Color '#FFB229')

    # Front lower panel gets a centered racing number badge and track stripe.
    Fill-MaterialGradient $graphics $frontLowerPanel (New-Color '#D9111C') (New-Color '#51050B') 90
    Draw-PanelTexture $graphics $frontLowerPanel 0 49 113 86 'Speckle' (New-Color '#FFBFC3')
    $frontLowerState = $graphics.Save()
    $graphics.SetClip($frontLowerPanel)
    $graphics.FillRectangle($raceWhite, 0, 83, 113, 9)
    $graphics.FillRectangle($raceBlack, 0, 92, 113, 5)
    $badgePath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $badgePath.AddEllipse(31, 59, 51, 51)
    $graphics.FillPath($raceWhite, $badgePath)
    $graphics.DrawPath($raceOutline, $badgePath)
    $numberFormat = New-Object System.Drawing.StringFormat
    $numberFormat.Alignment = [System.Drawing.StringAlignment]::Center
    $numberFormat.LineAlignment = [System.Drawing.StringAlignment]::Center
    $numberFont = New-Object System.Drawing.Font 'Arial Black', 24, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $graphics.DrawString('77', $numberFont, $raceBlack, (New-Object System.Drawing.RectangleF 31, 58, 51, 51), $numberFormat)
    $numberFont.Dispose()
    $numberFormat.Dispose()
    $badgePath.Dispose()
    $graphics.Restore($frontLowerState)
    Draw-PanelRivets $graphics $frontLowerPanel 0 49 113 86

    Fill-MaterialGradient $graphics $frontServicePanel (New-Color '#15181D') (New-Color '#4B1015') 90
    Draw-PanelTexture $graphics $frontServicePanel 268 268 112 62 'Brushed' (New-Color '#AEB6C0')
    $frontServiceState = $graphics.Save()
    $graphics.SetClip($frontServicePanel)
    Draw-Tachometer $graphics 305 302 22 (New-Color '#F7F7F2') (New-Color '#ED1723')
    foreach ($ventY in @(284, 290, 316, 322)) {
        $graphics.DrawLine($raceWhitePen, 337, $ventY, 368, $ventY)
    }
    $graphics.Restore($frontServiceState)
    Draw-PanelRivets $graphics $frontServicePanel 268 268 112 62

    Fill-MaterialGradient $graphics $topRoofPanel (New-Color '#0C0E12') (New-Color '#75060D') 20
    Draw-CheckeredPattern $graphics $topRoofPanel 268 331 112 91 12 (New-Color '#111318') (New-Color '#2F333A')
    Draw-RacingSpeedLines $graphics $topRoofPanel 277 383 91 (New-Color '#ED1723') (New-Color '#F7F7F2')
    Draw-PanelRivets $graphics $topRoofPanel 268 331 112 91

    Fill-MaterialGradient $graphics $topSecondaryPanel (New-Color '#C20D17') (New-Color '#250207') 0
    Draw-RacingSpeedLines $graphics $topSecondaryPanel 352 104 98 (New-Color '#F7F7F2') (New-Color '#FFB229')
    Draw-PanelRivets $graphics $topSecondaryPanel 346 90 112 45

    Fill-MaterialGradient $graphics $backLeftPanel (New-Color '#171A1F') (New-Color '#3C4149') 90
    Draw-CheckeredPattern $graphics $backLeftPanel 13 139 82 112 12 (New-Color '#111318') (New-Color '#353A42')
    # Back UV faces are quarter-turned relative to the physical cabinet. Draw
    # the two rear light bars vertically in UV space so they appear horizontal,
    # parallel and centered when the model is rendered in game.
    $rearLightState = $graphics.Save()
    $graphics.SetClip($backLeftPanel)
    $rearLightOuterPen = New-Object System.Drawing.Pen (New-Color '#F7F7F2'), 5.0
    $rearLightInnerPen = New-Object System.Drawing.Pen (New-Color '#ED1723'), 2.2
    foreach ($rearLightX in @(42, 67)) {
        $graphics.DrawLine($rearLightOuterPen, $rearLightX, 156, $rearLightX, 236)
        $graphics.DrawLine($rearLightInnerPen, $rearLightX, 158, $rearLightX, 234)
    }
    $rearLightInnerPen.Dispose()
    $rearLightOuterPen.Dispose()
    $graphics.Restore($rearLightState)
    Draw-PanelRivets $graphics $backLeftPanel 13 139 82 112

    Fill-MaterialGradient $graphics $backRightPanel (New-Color '#4B050B') (New-Color '#C70D18') 90
    Draw-PanelTexture $graphics $backRightPanel 137 139 89 112 'Brushed' (New-Color '#FFC2C5')
    $backRightState = $graphics.Save()
    $graphics.SetClip($backRightPanel)
    # Compensate for the same quarter-turn in the rear UV island. Without this
    # transform the tachometer is rendered sideways on the cabinet.
    $graphics.TranslateTransform(180, 188)
    $graphics.RotateTransform(90)
    $graphics.TranslateTransform(-180, -188)
    Draw-Tachometer $graphics 180 188 28 (New-Color '#F7F7F2') (New-Color '#FFB229')
    $graphics.Restore($backRightState)
    $backVentState = $graphics.Save()
    $graphics.SetClip($backRightPanel)
    # Vertical UV strokes become horizontal vents on the physical rear panel.
    foreach ($ventX in @(198, 205, 212)) {
        $graphics.DrawLine($raceOutline, $ventX, 214, $ventX, 243)
    }
    $graphics.Restore($backVentState)
    Draw-PanelRivets $graphics $backRightPanel 137 139 89 112

    if (-not [System.IO.File]::Exists($sidePosterFullPath)) {
        throw "Side poster artwork does not exist: $sidePosterFullPath"
    }

    $sidePosterSource = [System.Drawing.Image]::FromFile($sidePosterFullPath)
    $sidePoster = New-Object System.Drawing.Bitmap 268, 514
    $sidePosterGraphics = [System.Drawing.Graphics]::FromImage($sidePoster)
    $sidePosterGraphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $sidePosterGraphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $sidePosterGraphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $sidePosterGraphics.DrawImage(
        $sidePosterSource,
        (New-Object System.Drawing.Rectangle 0, 0, 268, 514),
        0, 0, $sidePosterSource.Width, $sidePosterSource.Height,
        [System.Drawing.GraphicsUnit]::Pixel
    )

    # Typography is deterministic and kept wholly inside the clean title plate.
    $raceTitleFormat = New-Object System.Drawing.StringFormat
    $raceTitleFormat.Alignment = [System.Drawing.StringAlignment]::Center
    $raceTitleFormat.LineAlignment = [System.Drawing.StringAlignment]::Center
    $raceTitleFont = New-Object System.Drawing.Font 'Microsoft YaHei', 20, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $raceTitleShadow = New-Object System.Drawing.SolidBrush (New-Color '#050608')
    $raceTitleWhite = New-Object System.Drawing.SolidBrush (New-Color '#F7F7F2')
    $sidePosterGraphics.DrawString($racingDisplayName, $raceTitleFont, $raceTitleShadow, (New-Object System.Drawing.RectangleF 42, 28, 188, 28), $raceTitleFormat)
    $sidePosterGraphics.DrawString($racingDisplayName, $raceTitleFont, $raceTitleWhite, (New-Object System.Drawing.RectangleF 41, 27, 188, 28), $raceTitleFormat)

    foreach ($face in $faces | Where-Object { $_.Material -eq 'RightSide' }) {
        Draw-SidePosterOnFace $graphics $face $sidePoster $false
    }
    foreach ($face in $faces | Where-Object { $_.Material -eq 'LeftSide' }) {
        Draw-SidePosterOnFace $graphics $face $sidePoster $true
    }

    $raceTitleWhite.Dispose()
    $raceTitleShadow.Dispose()
    $raceTitleFont.Dispose()
    $raceTitleFormat.Dispose()
    $sidePosterGraphics.Dispose()
    $sidePoster.Dispose()
    $sidePosterSource.Dispose()
    $raceRedPen.Dispose()
    $raceWhitePen.Dispose()
    $raceOutline.Dispose()
    $raceSilver.Dispose()
    $raceAmber.Dispose()
    $raceDarkRed.Dispose()
    $raceRed.Dispose()
    $raceBlack.Dispose()
    $raceWhite.Dispose()
}

$white = New-Object System.Drawing.SolidBrush (New-Color '#F7FCFF')
$cyan = New-Object System.Drawing.SolidBrush (New-Color '#53E1EA')
$yellow = New-Object System.Drawing.SolidBrush (New-Color '#FFD735')
$red = New-Object System.Drawing.SolidBrush (New-Color '#F2253D')
$navy = New-Object System.Drawing.SolidBrush (New-Color '#10182B')
$blue = New-Object System.Drawing.SolidBrush (New-Color '#183BCB')

# Front marquee: H0-K0 in the locator texture.
$marqueeFace = $faces | Where-Object { $_.Material -eq 'Top' -and $_.Index -eq 3 }
$marqueePath = New-Object System.Drawing.Drawing2D.GraphicsPath
$marqueePath.AddPolygon([System.Drawing.PointF[]]$marqueeFace.Points)
$marqueeState = $graphics.Save()
$graphics.SetClip($marqueePath)
$marqueeStartColor = if ($Theme -eq 'Racing') { New-Color '#101216' } else { New-Color '#1438DE' }
$marqueeEndColor = if ($Theme -eq 'Racing') { New-Color '#D7111C' } else { New-Color '#F02B3F' }
$marqueeBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Rectangle 232, 0, 116, 28),
    $marqueeStartColor,
    $marqueeEndColor,
    0
)
$graphics.FillRectangle($marqueeBrush, 230, -2, 120, 32)
$marqueeBrush.Dispose()
if ($Theme -in @('PillLabPolished', 'Racing')) {
    Draw-PanelTexture $graphics $marqueePath 232 0 116 28 'Brushed' (New-Color '#FFFFFF')
    Draw-PanelRivets $graphics $marqueePath 232 0 116 28
    # Draw exact lettering after generation so the front marquee is never
    # cropped, misspelled or split across unrelated source art.
    $marqueeTitle = if ($Theme -eq 'Racing') { $racingDisplayName } else { 'PIQ PILL LAB' }
    $marqueeFontFamily = if ($Theme -eq 'Racing') { 'Microsoft YaHei' } else { 'Arial Black' }
    $marqueeFontSize = if ($Theme -eq 'Racing') { 14 } else { 10 }
    $marqueeFont = New-Object System.Drawing.Font $marqueeFontFamily, $marqueeFontSize, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $marqueeFormat = New-Object System.Drawing.StringFormat
    $marqueeFormat.Alignment = [System.Drawing.StringAlignment]::Center
    $marqueeFormat.LineAlignment = [System.Drawing.StringAlignment]::Center
    $graphics.DrawString(
        $marqueeTitle,
        $marqueeFont,
        $navy,
        (New-Object System.Drawing.RectangleF 233, 2, 114, 24),
        $marqueeFormat
    )
    $graphics.DrawString(
        $marqueeTitle,
        $marqueeFont,
        $white,
        (New-Object System.Drawing.RectangleF 232, 1, 114, 24),
        $marqueeFormat
    )
    $marqueeFormat.Dispose()
    $marqueeFont.Dispose()
} else {
    $marqueeFont = New-Object System.Drawing.Font 'Arial', 11, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $isPillLabTheme = $Theme -eq 'PillLab'
    $marqueeText = if ($isPillLabTheme) { 'PIQ PILL LAB' } else { 'DR. MARIO' }
    $marqueeX = if ($isPillLabTheme) { 242 } else { 249 }
    $graphics.DrawString($marqueeText, $marqueeFont, $navy, $marqueeX + 1, 7)
    $graphics.DrawString($marqueeText, $marqueeFont, $white, $marqueeX, 6)
    $marqueeFont.Dispose()
}
$graphics.Restore($marqueeState)
$marqueePath.Dispose()

$sideTitleFont = New-Object System.Drawing.Font 'Arial', 14, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
$sideTitleSmallFont = New-Object System.Drawing.Font 'Arial', 10, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)

if ($Theme -eq 'PillLab') {
    $rightPanelState = $graphics.Save()
    $graphics.SetClip($rightUpperPanel)
    Draw-Ecg $graphics 48 342 58 (New-Color '#D9FAFC')
    Draw-Capsule $graphics 52 310 48 19 (New-Color '#153FE0') (New-Color '#F12A3F') -18
    Draw-Cross $graphics 75 350 25 $white
    $graphics.Restore($rightPanelState)

    $rightLowerState = $graphics.Save()
    $graphics.SetClip($rightLowerPanel)
    Draw-Cross $graphics 83 453 34 $white
    Draw-Capsule $graphics 56 478 50 17 (New-Color '#F12A3F') (New-Color '#F8FCFF') 8
    $ventPen = New-Object System.Drawing.Pen (New-Color '#087D98'), 1.2
    foreach ($ventY in @(429, 434, 439)) {
        $graphics.DrawLine($ventPen, 101, $ventY, 121, $ventY)
    }
    $ventPen.Dispose()
    $graphics.Restore($rightLowerState)

    $sideTitleState = $graphics.Save()
    $graphics.SetClip($leftUpperPanel)
    Draw-Hexagon $graphics 178 353 11 (New-Color '#C7F8FA')
    Draw-Hexagon $graphics 207 301 9 (New-Color '#C7F8FA')
    Draw-Hexagon $graphics 214 357 7 (New-Color '#C7F8FA')
    $graphics.DrawString('PIQ', $sideTitleSmallFont, $navy, 180, 299)
    $graphics.DrawString('PIQ', $sideTitleSmallFont, $yellow, 179, 298)
    $graphics.DrawString('PILL', $sideTitleFont, $navy, 173, 316)
    $graphics.DrawString('PILL', $sideTitleFont, $white, 172, 315)
    $graphics.DrawString('LAB', $sideTitleSmallFont, $navy, 184, 339)
    $graphics.DrawString('LAB', $sideTitleSmallFont, $white, 183, 338)
    $graphics.Restore($sideTitleState)

    $germState = $graphics.Save()
    $graphics.SetClip($leftLowerPanel)
    $bubblePen = New-Object System.Drawing.Pen (New-Color '#D7FAFC'), 1
    foreach ($bubble in @(
        @(154, 428, 5),
        @(214, 430, 4),
        @(155, 480, 4),
        @(215, 485, 6)
    )) {
        $graphics.DrawEllipse($bubblePen, $bubble[0], $bubble[1], $bubble[2], $bubble[2])
    }
    $bubblePen.Dispose()
    Draw-Germ $graphics 169 446 24 (New-Color '#147AE8')
    Draw-Germ $graphics 198 446 24 (New-Color '#F03A43')
    Draw-Germ $graphics 184 476 24 (New-Color '#FFD735')
    $graphics.Restore($germState)
} elseif ($Theme -eq 'DrMario') {
    # Opposite upper side: compact stacked title, fully contained in the broad side panel.
    $sideTitleState = $graphics.Save()
    $graphics.SetClip($leftUpperPanel)
    $graphics.DrawString('DR.', $sideTitleSmallFont, $navy, 174, 302)
    $graphics.DrawString('DR.', $sideTitleSmallFont, $white, 173, 301)
    $graphics.DrawString('MARIO', $sideTitleFont, $navy, 164, 322)
    $graphics.DrawString('MARIO', $sideTitleFont, $yellow, 163, 321)
    Draw-Capsule $graphics 175 346 43 14 (New-Color '#153FE0') (New-Color '#F12A3F') -8
    $graphics.Restore($sideTitleState)
}

# Side marquees use simple seam-safe marks unless a detailed continuous poster is active.
if ($Theme -notin @('PillLabPolished', 'Racing')) {
    Draw-Capsule $graphics 18 263 54 14 (New-Color '#153FE0') (New-Color '#F12A3F') 0
    Draw-Capsule $graphics 190 263 54 14 (New-Color '#F12A3F') (New-Color '#153FE0') 0
}

if ($Theme -notin @('PillLabPolished', 'Racing')) {
    $roofState = $graphics.Save()
    $graphics.SetClip($paths['Top'])
    Draw-Capsule $graphics 293 364 62 20 (New-Color '#153FE0') (New-Color '#F12A3F') 10
    $graphics.Restore($roofState)
}

# Actual monitor face: J-D through L-F. Keep it dark and neutral for the live emulator overlay.
$screenFace = $faces | Where-Object { $_.Material -eq 'Screen' -and $_.Index -eq 7 }
$screenPath = New-Object System.Drawing.Drawing2D.GraphicsPath
$screenPath.AddPolygon([System.Drawing.PointF[]]$screenFace.Points)
$screenBrush = New-Object System.Drawing.SolidBrush (New-Color '#02060A')
$graphics.FillPath($screenBrush, $screenPath)
$screenBrush.Dispose()

# Small medical decorations on front/back/control surfaces. Racing owns its
# complete decoration pass above and must not inherit pills or crosses here.
if ($Theme -ne 'Racing') {
    Draw-Cross $graphics 50 91 26 $white
    Draw-Cross $graphics 416 56 21 $white
    if ($Theme -ne 'PillLabPolished') {
        Draw-Cross $graphics 408 230 22 $white
    }
    if ($Theme -eq 'PillLab') {
        $controlPen = New-Object System.Drawing.Pen (New-Color '#10182B'), 2
        $graphics.FillEllipse($navy, 12, 33, 16, 8)
        $graphics.DrawLine($controlPen, 20, 34, 20, 24)
        $graphics.FillEllipse($red, 15, 18, 11, 11)
        $graphics.FillEllipse($red, 56, 24, 12, 12)
        $graphics.FillEllipse($blue, 75, 24, 12, 12)
        $graphics.FillEllipse($yellow, 94, 24, 12, 12)
        $controlPen.Dispose()
    } elseif ($Theme -eq 'DrMario') {
        Draw-Capsule $graphics 18 24 48 17 (New-Color '#153FE0') (New-Color '#F12A3F') -8
    }
    Draw-Capsule $graphics 283 98 42 16 (New-Color '#F12A3F') (New-Color '#F8FCFF') 15
    Draw-Capsule $graphics 417 288 34 13 (New-Color '#153FE0') (New-Color '#F12A3F') -30
}

if ($Theme -notin @('PillLabPolished', 'Racing')) {
    $backState = $graphics.Save()
    $graphics.SetClip($paths['Back'])
    Draw-Cross $graphics 54 194 34 $white
    Draw-Capsule $graphics 154 184 58 20 (New-Color '#153FE0') (New-Color '#F12A3F') -10
    Draw-Ecg $graphics 145 222 70 (New-Color '#D9FAFC')
    $graphics.Restore($backState)
}

# Flat themes need explicit UV boundaries. The polished source already contains designed
# panel borders; outlining every OBJ triangle/quad would expose the mesh topology as noise.
if ($Theme -notin @('PillLabPolished', 'Racing')) {
    $outlinePen = New-Object System.Drawing.Pen (New-Color '#151628'), 1.4
    foreach ($face in $faces) {
        $facePath = New-Object System.Drawing.Drawing2D.GraphicsPath
        $facePath.AddPolygon([System.Drawing.PointF[]]$face.Points)
        $graphics.DrawPath($outlinePen, $facePath)
        $facePath.Dispose()
    }
    $outlinePen.Dispose()
}

$screenPath.Dispose()
$sideTitleFont.Dispose()
$sideTitleSmallFont.Dispose()
$white.Dispose()
$cyan.Dispose()
$yellow.Dispose()
$red.Dispose()
$navy.Dispose()
$blue.Dispose()
if ($null -ne $source) {
    $source.Dispose()
}
$rightUpperPanel.Dispose()
$rightLowerPanel.Dispose()
$leftUpperPanel.Dispose()
$leftLowerPanel.Dispose()
$rightMarqueeSide.Dispose()
$leftMarqueeSide.Dispose()
$controllerTop.Dispose()
$backLeftPanel.Dispose()
$backRightPanel.Dispose()
$frontLowerPanel.Dispose()
$topRoofPanel.Dispose()

foreach ($path in $paths.Values) {
    $path.Dispose()
}

$bitmap.Save($outputFullPath, [System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose()
$bitmap.Dispose()

Write-Output $outputFullPath
