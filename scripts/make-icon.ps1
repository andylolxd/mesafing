# Dibuja el ícono de mesafing (WiFi blanco sobre degradé) en todas las densidades de Android.
Add-Type -AssemblyName System.Drawing

$res = Join-Path $PSScriptRoot '..\android\app\src\main\res'
$c1 = [System.Drawing.ColorTranslator]::FromHtml('#7C3AED')
$c2 = [System.Drawing.ColorTranslator]::FromHtml('#4F46E5')
$c3 = [System.Drawing.ColorTranslator]::FromHtml('#06B6D4')

function Fill-Background($g, [int]$size, $shape) {
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush ([System.Drawing.Point]::new(0, 0)), ([System.Drawing.Point]::new($size, $size)), $c1, $c3
    $blend = New-Object System.Drawing.Drawing2D.ColorBlend 3
    $blend.Colors = @($c1, $c2, $c3)
    $blend.Positions = @([single]0, [single]0.45, [single]1)
    $brush.InterpolationColors = $blend
    if ($shape) { $g.FillPath($brush, $shape) } else { $g.FillRectangle($brush, 0, 0, $size, $size) }
    $brush.Dispose()

    # Brillo suave arriba que se desvanece hacia abajo (el pincel abarca todo el alto para no repetirse).
    $gloss = New-Object System.Drawing.Drawing2D.LinearGradientBrush ([System.Drawing.Point]::new(0, -1)), ([System.Drawing.Point]::new(0, $size + 1)), ([System.Drawing.Color]::FromArgb(50, 255, 255, 255)), ([System.Drawing.Color]::FromArgb(0, 255, 255, 255))
    $gb = New-Object System.Drawing.Drawing2D.Blend 3
    $gb.Factors = @([single]0, [single]1, [single]1)
    $gb.Positions = @([single]0, [single]0.55, [single]1)
    $gloss.Blend = $gb
    if ($shape) { $g.FillPath($gloss, $shape) } else { $g.FillRectangle($gloss, 0, 0, $size, $size) }
    $gloss.Dispose()
}

function Draw-Wifi($g, [float]$cx, [float]$cy, [float]$u, $color) {
    $stroke = 0.15 * $u
    foreach ($r in @(0.36, 0.62, 0.88)) {
        $rr = $r * $u
        $pen = New-Object System.Drawing.Pen $color, $stroke
        $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
        $g.DrawArc($pen, $cx - $rr, $cy - $rr, 2 * $rr, 2 * $rr, 225, 90)
        $pen.Dispose()
    }
    $d = 0.24 * $u
    $brush = New-Object System.Drawing.SolidBrush $color
    $g.FillEllipse($brush, $cx - $d / 2, $cy - $d / 2, $d, $d)
    $brush.Dispose()
}

function Draw-Symbol($g, [int]$size, [float]$u) {
    # Centra verticalmente desde el borde del arco exterior hasta el punto inferior.
    $top = 0.88 * $u + 0.075 * $u
    $bottom = 0.12 * $u
    $cy = $size / 2 + ($top - $bottom) / 2
    $cx = $size / 2
    Draw-Wifi $g $cx ($cy + 0.035 * $u) $u ([System.Drawing.Color]::FromArgb(55, 20, 10, 60))
    Draw-Wifi $g $cx $cy $u ([System.Drawing.Color]::White)
}

function Rounded-Square([int]$size) {
    $rad = $size * 0.22
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $p.AddArc(0, 0, 2 * $rad, 2 * $rad, 180, 90)
    $p.AddArc($size - 2 * $rad, 0, 2 * $rad, 2 * $rad, 270, 90)
    $p.AddArc($size - 2 * $rad, $size - 2 * $rad, 2 * $rad, 2 * $rad, 0, 90)
    $p.AddArc(0, $size - 2 * $rad, 2 * $rad, 2 * $rad, 90, 90)
    $p.CloseFigure()
    return $p
}

function New-Icon([int]$size, [string]$mode, [string]$path) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)

    if ($mode -eq 'square') {
        $p = Rounded-Square $size
        Fill-Background $g $size $p
        $p.Dispose()
        Draw-Symbol $g $size ($size * 0.42)
    } elseif ($mode -eq 'round') {
        $p = New-Object System.Drawing.Drawing2D.GraphicsPath
        $p.AddEllipse(0, 0, $size, $size)
        Fill-Background $g $size $p
        $p.Dispose()
        Draw-Symbol $g $size ($size * 0.40)
    } elseif ($mode -eq 'bg') {
        Fill-Background $g $size $null
    } elseif ($mode -eq 'fg') {
        # El ícono adaptativo recorta el borde: el símbolo tiene que entrar en el 61% central.
        Draw-Symbol $g $size ($size * 0.33)
    }

    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

$densities = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($k in $densities.Keys) {
    $dir = Join-Path $res "mipmap-$k"
    $s = $densities[$k]
    New-Icon $s 'square' (Join-Path $dir 'ic_launcher.png')
    New-Icon $s 'round' (Join-Path $dir 'ic_launcher_round.png')
    New-Icon ([int]($s * 2.25)) 'fg' (Join-Path $dir 'ic_launcher_foreground.png')
    New-Icon ([int]($s * 2.25)) 'bg' (Join-Path $dir 'ic_launcher_background.png')
}
New-Icon 512 'square' (Join-Path $PSScriptRoot 'icon-preview.png')
Write-Output 'Iconos generados'
