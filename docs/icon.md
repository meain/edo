# App Icon

## Design

Three isometric cubes offset diagonally — like a deck of cards seen in perspective. Represents building blocks, the idea of constructing and stacking lifetime agents.

- **Background**: `#FAFAFA` (near-white)
- **Style**: line art, monochrome greys, depth via face shading and stroke weight
- **Three cubes**: back (faintest, lightest stroke) → middle → front (sharpest, darkest outline `#18181B`)

The depth effect comes from two cues: progressively darker strokes on closer cubes, and slightly lighter fill on top faces vs. side faces.

## Files

| File | Purpose |
|------|---------|
| `app/src/main/res/drawable/ic_launcher_background.xml` | Solid `#FAFAFA` fill |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Cube artwork |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | White faces at varying alpha for Material You theming |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive icon manifest |

## Geometry

Viewport: 108×108dp. Design centered at (54, 54). Each cube is an isometric rhombus (top face) + two parallelogram faces (left/right).

**Back cube** — stroke `#D1D5DB` / 0.9px
```
Top:   M 57.7,28.6 L 75.5,38.8 L 57.7,49   L 39.9,38.8 Z
Left:  M 39.9,38.8 L 57.7,49   L 57.7,68.4 L 39.9,58.2 Z
Right: M 75.5,38.8 L 57.7,49   L 57.7,68.4 L 75.5,58.2 Z
Fill:  #F3F4F6 / #EBEBEB / #E0E0E0
```

**Middle cube** — stroke `#9CA3AF` / 1.1px
```
Top:   M 54.1,33.7 L 71.9,43.9 L 54.1,54.1 L 36.3,43.9 Z
Left:  M 36.3,43.9 L 54.1,54.1 L 54.1,73.5 L 36.3,63.3 Z
Right: M 71.9,43.9 L 54.1,54.1 L 54.1,73.5 L 71.9,63.3 Z
Fill:  #F3F4F6 / #E5E7EB / #DCDCDC
```

**Front cube** — stroke `#18181B` / 1.6px
```
Top:   M 50.5,38.8 L 68.3,49   L 50.5,59.2 L 32.7,49   Z
Left:  M 32.7,49   L 50.5,59.2 L 50.5,78.6 L 32.7,68.4 Z
Right: M 68.3,49   L 50.5,59.2 L 50.5,78.6 L 68.3,68.4 Z
Fill:  #FAFAFA / #E5E7EB / #D1D5DB
```

## Monochrome (Material You)

Same geometry, white fills at varying `fillAlpha` to preserve depth under system tinting:

| Cube | Top α | Left α | Right α |
|------|-------|--------|---------|
| Back | 0.35 | 0.25 | 0.30 |
| Middle | 0.65 | 0.50 | 0.55 |
| Front | 1.00 | 0.80 | 0.90 |

## Sizing notes

The safe zone for adaptive icons is the center 66×66dp (21–87dp in each axis). The design fits within roughly x:32–76, y:28–79 — leaving comfortable padding on all sides so the cubes don't clip under any launcher shape (circle, squircle, rounded square).

To resize: scale all coordinates relative to center (54, 54). Current scale is ~0.42× of a 200×200 reference design.
