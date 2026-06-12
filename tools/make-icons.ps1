Add-Type -AssemblyName System.Drawing

$srcPath = (Resolve-Path "App-Symbol_ObsidiDine.jpeg").Path
$src = [System.Drawing.Bitmap]::FromFile($srcPath)

# Kachel finden: Histogramm heller Pixel pro Spalte/Zeile. Einzelne Glitzer-
# Sterne im Hintergrund erzeugen nur wenige Treffer und fallen unter die
# Mindestanzahl — nur die kompakte Kachel bleibt übrig.
$bg = $src.GetPixel(2, 2)
$colHits = New-Object int[] $src.Width
$rowHits = New-Object int[] $src.Height
for ($y = 0; $y -lt $src.Height; $y++) {
    for ($x = 0; $x -lt $src.Width; $x++) {
        $p = $src.GetPixel($x, $y)
        $d = [Math]::Abs($p.R - $bg.R) + [Math]::Abs($p.G - $bg.G) + [Math]::Abs($p.B - $bg.B)
        if ($d -gt 60) { $colHits[$x]++; $rowHits[$y]++ }
    }
}
$minHits = 40
$minX = 0; while ($minX -lt $src.Width - 1 -and $colHits[$minX] -lt $minHits) { $minX++ }
$maxX = $src.Width - 1; while ($maxX -gt 0 -and $colHits[$maxX] -lt $minHits) { $maxX-- }
$minY = 0; while ($minY -lt $src.Height - 1 -and $rowHits[$minY] -lt $minHits) { $minY++ }
$maxY = $src.Height - 1; while ($maxY -gt 0 -and $rowHits[$maxY] -lt $minHits) { $maxY-- }

# Quadratisch machen (Kachel ist rund-quadratisch), an Bildgrenzen klemmen
$w = $maxX - $minX; $h = $maxY - $minY; $side = [Math]::Max($w, $h)
$side = [Math]::Min($side, [Math]::Min($src.Width, $src.Height))
$cx = ($minX + $maxX) / 2; $cy = ($minY + $maxY) / 2
$left = [int][Math]::Max(0, [Math]::Min($cx - $side / 2, $src.Width - $side))
$top = [int][Math]::Max(0, [Math]::Min($cy - $side / 2, $src.Height - $side))
$tile = $src.Clone([System.Drawing.Rectangle]::new($left, $top, $side, $side), $src.PixelFormat)
Write-Host "Tile: ${side}x${side} @ $left,$top (Quelle: $($src.Width)x$($src.Height))"

function New-Icon([int]$canvas, [double]$scale, [string]$outPath) {
    $bmp = New-Object System.Drawing.Bitmap($canvas, $canvas)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = "AntiAlias"
    $g.InterpolationMode = "HighQualityBicubic"
    $size = [int]($canvas * $scale)
    $off = [int](($canvas - $size) / 2)
    # Rounded-Rect-Clip (Radius 22% wie iOS/Play-Kacheln), Ecken transparent
    $r = [int]($size * 0.22)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc($off, $off, 2 * $r, 2 * $r, 180, 90)
    $path.AddArc($off + $size - 2 * $r, $off, 2 * $r, 2 * $r, 270, 90)
    $path.AddArc($off + $size - 2 * $r, $off + $size - 2 * $r, 2 * $r, 2 * $r, 0, 90)
    $path.AddArc($off, $off + $size - 2 * $r, 2 * $r, 2 * $r, 90, 90)
    $path.CloseFigure()
    $g.SetClip($path)
    $g.DrawImage($tile, $off, $off, $size, $size)
    $g.Dispose()
    New-Item -ItemType Directory -Force (Split-Path $outPath) | Out-Null
    $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "OK: $outPath"
}

$res = "android/app/src/main/res"
$densities = @{ mdpi = 1.0; hdpi = 1.5; xhdpi = 2.0; xxhdpi = 3.0; xxxhdpi = 4.0 }
foreach ($d in $densities.Keys) {
    $f = $densities[$d]
    # Legacy-Icon: Kachel füllt Canvas (48dp-Basis)
    New-Icon ([int](48 * $f)) 1.0 "$res/mipmap-$d/ic_launcher.png"
    Copy-Item "$res/mipmap-$d/ic_launcher.png" "$res/mipmap-$d/ic_launcher_round.png"
    # Adaptive Foreground: 108dp-Canvas, Kachel auf 60% (Safe Zone 66/108)
    New-Icon ([int](108 * $f)) 0.6 "$res/mipmap-$d/ic_launcher_foreground.png"
}
$src.Dispose(); $tile.Dispose()
