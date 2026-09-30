# scripts

## `configure_firebase.py`

Seeds the Firestore `config` collection that the app reads at runtime. Each top-level
key of `config.json` becomes one document:

| Document | Contents |
|---|---|
| `triggers` | `apps`: the apps whose launch starts a capture (`packageName`, `enable`, `appName`, `category`) |
| `triggerDuration` | capture length in ms for `unlockEvent`, `app`, `flowerGame`, `stroopTask` |
| `stroopTask` | `rounds`: Stroop responses per session |
| `survey` | `preLink` / `postLink`: survey URLs |

Existing documents with the same id are overwritten. See
[../docs/data-schema.md](../docs/data-schema.md) for details.

### Setup

1. In the Firebase console, go to **Project settings → Service accounts → Generate new
   private key**. Save the key under `scripts/cred/`, which is gitignored.
2. Install the dependency:

   ```bash
   cd scripts
   python3 -m venv venv && source venv/bin/activate
   pip install -r requirements.txt
   ```

### Run

```bash
python configure_firebase.py --cred ./cred/<your-key>.json --config config.json
```

The defaults are `--cred ./cred/filename.json` and `--config config.json`, both
relative to the current directory.
