# web front end

`index.html` is the complete front end: a dependency-free static page that
talks to the core's HTTP+SSE API (`src/adhush/ipc/api.py`, ADR 0006).

```sh
adhush run          # with [ipc] enabled = true in config
open index.html     # file:// works; CORS is permissive
```

- Live state via `EventSource(/events)`; commands via `fetch(/command)`.
- With a `token` configured the page falls back to polling (EventSource
  cannot send headers); keep tokenless use on loopback only.
- In-browser *capture* (tab capture + WASM detectors, per the roadmap table)
  is future work; today the browser is the control surface, not the sensor.
