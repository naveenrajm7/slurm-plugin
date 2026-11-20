# Slurm REST API versions: how the plugin handles them and how to add one

## How it works

slurmrestd puts a version in every path (`/slurm/v0.0.42/jobs/`). Each Slurm release serves
several versions and removes the oldest about two years after adding it:

| REST API version | Added in Slurm | Removed in Slurm |
|------------------|----------------|------------------|
| `v0.0.42` | 24.11 | 26.11 |
| `v0.0.43` | 25.05 | 27.05 |
| `v0.0.44` | 25.11 | 27.11 |
| `v0.0.45` | 26.05 | 28.05 |

Source: [REST API client guide, data_parser lifecycle](https://slurm.schedmd.com/rest_clients.html).

The plugin talks to all versions in `SlurmApiVersion` with one generated client:

- **One set of generated types.** The client is generated from
  `src/main/openapi/client/slurm-client.json`. That file is the reference spec
  (`SlurmApiVersion.REFERENCE`, currently `v0.0.42`) with the version removed from schema names
  and operation ids, so the classes are `JobDescMsg` and `slurmPostJobSubmit()`, not
  `V0042JobDescMsg`.
- **The version in the path is chosen at runtime.** `SlurmClient` replaces the version segment of
  each request in its request interceptor.
- **Detection.** With no version configured, the first call pings each supported version, newest
  first, and keeps the first that is not a 404. The result lives as long as the cached client
  (30 minutes), so a cluster upgrade is picked up without a restart. A cloud can also be pinned
  with **REST API version** (`apiVersion` in JCasC).
- **Lenient parsing.** Unknown response fields are ignored, and an enum value this client does not
  know parses to a sentinel instead of failing the response (`enumUnknownDefaultCase`).

This works because the plugin uses six operations and a few dozen fields:

| Operation | Used for |
|-----------|----------|
| `GET /slurm/{v}/ping/` | Test Connection, version detection |
| `POST /slurm/{v}/job/submit` | Submitting the agent job |
| `DELETE /slurm/{v}/jobs/` | Cancelling a job |
| `GET /slurm/{v}/jobs/` | Garbage collection of orphaned jobs |
| `GET /slurm/{v}/jobs/state/` | Job state |
| `GET /slurmdb/{v}/job/{job_id}` | Job state and nodes from accounting |

Status and cancel use the plural endpoints on purpose: the singular `/job/{id}` handler rejects
federated job ids. Keep that when adding a version.

## What is checked

`SlurmApiCompatibilityTest` reads every `src/main/openapi/slurm-v*.json` and checks that:

- the six operations exist in that version;
- every field the plugin reads or writes exists and has the same type, format and referenced
  schema as in the reference spec;
- `client/slurm-client.json` is exactly the reference spec with version prefixes removed.

`SlurmClientVersionTest` runs the client against a local HTTP server that serves a chosen set of
versions, and checks detection, the configured override, and that all six calls go to the selected
version on both `/slurm/` and `/slurmdb/`.

Specs for all four versions are in the repository. `slurm-v0.0.42.json` was generated on a
24.11 cluster. The `v0.0.43`, `v0.0.44` and `v0.0.45` specs come from SchedMD's
[SlinkyProject/slurm-client](https://github.com/SlinkyProject/slurm-client) (`api/v00NN/slurm-openapi.gen.json`,
commit `17ec635`), generated from Slurm 25.05.8, 25.11.8 and 26.05.3.

Known differences the plugin tolerates:

- `v0.0.45` drops `pinged` and `mode` from the ping response (both were deprecated). They are only
  shown in Test Connection, which leaves those lines out.
- Specs differ in whether a named array such as `job_state` is written inline or as a reference.
  The wire format is the same, and the test follows such references before comparing.

If a supported version ever has no spec, `everySupportedVersionHasASpec` is reported as skipped,
not failed, to mark that version as unverified.

## Adding a REST API version

1. Generate the spec on a cluster that serves the version:

   ```bash
   slurmrestd --generate-openapi-spec -s slurmctld,slurmdbd -d v0.0.46 > slurm-v0.0.46.json
   ```

2. Copy it to `src/main/openapi/slurm-v0.0.46.json`.

3. Add the constant to `SlurmApiVersion`, at the top (the order is newest first), with the Slurm
   releases that add and remove it. Skip this if the constant already exists.

4. Run the compatibility test:

   ```bash
   mvn test -Dtest=SlurmApiCompatibilityTest
   ```

   If it passes, the shared client is safe for the new version. If it reports a missing or
   changed field, either stop using that field, or handle the difference in `SlurmClient` or
   `SlurmJobBuilder` for that version.

5. Update the tables in this file and in `README.md`. The dropdown on the cloud page is built
   from the enum, so it needs no change.

6. Test against a real cluster: Test Connection should show the new version, then run one build.

## Removing a REST API version

Remove the constant from `SlurmApiVersion` and delete its spec. A cloud that was pinned to the
removed version falls back to auto-detect and logs a warning.

If the removed version is the reference, move the reference first (next section).

## Changing the reference spec

Do this when the plugin needs a field that only exists in a newer version, or before removing the
current reference version.

1. Point `SlurmApiVersion.REFERENCE` at the new version.
2. Regenerate the client spec (the only step that needs Python, and only on a developer machine):

   ```bash
   python scripts/preprocess-openapi-spec.py \
       src/main/openapi/slurm-v0.0.44.json \
       src/main/openapi/client/slurm-client.json
   ```

3. `mvn clean test`. Compile errors in `SlurmClient`, `SlurmJobBuilder` or the tests show where a
   generated type changed. The compatibility test then shows which older versions differ from the
   new reference.

## Troubleshooting

**"serves none of the REST API versions this plugin supports"**: the server answered 404 on the
ping of every supported version. Either the URL is wrong (it should be the slurmrestd base URL,
without `/slurm`), or the cluster is newer or older than this plugin release supports. Check what
the server serves:

```bash
curl -s -H "X-SLURM-USER-TOKEN: $SLURM_JWT" http://controller:6820/openapi/v3 | grep -o '/slurm/v[0-9.]*/ping' | sort -u
```

**A build works on one version and fails on another**: pin the cloud to the working version with
**REST API version**, then compare the two specs for the field involved and add it to the
`FIELDS` list in `SlurmApiCompatibilityTest`.
