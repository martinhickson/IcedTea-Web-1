# IcedTea-Web sample apps

Single Angular dev server hosting multiple JNLP samples for manual IcedTea-Web verification.

## Layout

```
sample-apps/
  src/                 Angular catalog UI
  scripts/             shared JNLP build + launch + test
  public/jnlp/         built JNLP + JAR per sample
  swing-gui/           Swing GUI Java sample
  console/             console stdout sample (no JNLP favicon)
```

## Quick start

```powershell
cd sample-apps
npm install
npm start
```

- Catalog: http://127.0.0.1:4200/
- Swing GUI JNLP: http://127.0.0.1:4200/jnlp/swing-gui/app.jnlp
- Console JNLP: http://127.0.0.1:4200/jnlp/console/app.jnlp

## Commands

| Command | Purpose |
|---------|---------|
| `npm run build:jnlp` | Build all samples |
| `npm run build:jnlp:console` | Build one sample |
| `npm run launch:console` | Launch console sample via `javawsc` |
| `npm run test:console` | Verbose console launch + log assertions |

Set `JDK17_HOME` if JDK 17 auto-detection fails.
