# C4 model

[C4](https://c4model.com/) architecture diagrams of the FacePsy system, written in the
[Structurizr DSL](https://docs.structurizr.com/dsl).

- `workspace.dsl`: the model and views (system context, containers, app components).
- `01-context.md`: documentation shown next to the diagrams.

The model covers the wider study setup. The compliance dashboard, BigQuery and Qualtrics
are external systems and are **not** in this repository. For the code-level design of the
app, see [../docs/architecture.md](../docs/architecture.md).

## View the diagrams

Run Structurizr Lite from the repository root:

```bash
docker run -it --rm -p 8080:8080 -v "$(pwd)/c4model:/usr/local/structurizr" structurizr/lite
```

Then open http://localhost:8080.
