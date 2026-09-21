# GitHub API schemas

- **`schemas/` holds the endpoint shapes.** It is gitignored;
  `python3 download-schemas.py --filter '/orgs/{org}/...'` recreates the
  part you need. Check a new record's field names and enums there before
  writing it — `dependabot_delegated_alert_dismissal` exists,
  `code_security` is a separate field from `advanced_security`, and the
  collaborator listing carries `permissions` booleans beside `role_name`.

`download-schemas.py` downloads the official GitHub REST API OpenAPI spec and
extracts per-endpoint schemas and example responses into `schemas/` for local
reference.

### Source

GitHub publishes their OpenAPI spec at
[github/rest-api-description](https://github.com/github/rest-api-description).
The script uses the **dereferenced** variant so all `$ref` links are resolved
to inline values, meaning example responses contain real data rather than
pointers.

### Running the script

```bash
# Default: 2026-03-10 spec, repo/org/user endpoints
python3 download-schemas.py

# Different API version
python3 download-schemas.py --api-version 2022-11-28

# Add extra path prefix (replaces the defaults)
python3 download-schemas.py --filter /repos/{owner}/{repo}/branches

# Custom output directory
python3 download-schemas.py --output-dir /tmp/schemas
```

### Output structure

`schemas/` is gitignored (the full run produces ~900 files, ~83 MB).

```
schemas/
├── openapi.json                                        # Full dereferenced spec
├── orgs/{org}/repos/
│   ├── get/
│   │   ├── schema.json                                 # Endpoint definition
│   │   └── example-200-default.json                   # Example response
│   └── post/
│       └── schema.json
├── repos/{owner}/{repo}/
│   ├── get/
│   │   ├── schema.json
│   │   └── example-200-default-response.json
│   └── patch/
│       ├── schema.json
│       └── example-200-default.json
├── repos/{owner}/{repo}/branches/{branch}/protection/
│   ├── get/
│   ├── put/
│   └── delete/
├── repos/{owner}/{repo}/actions/permissions/workflow/
│   ├── get/
│   └── put/
└── ...
```

Each `schema.json` contains the full OpenAPI operation object: `summary`,
`parameters`, `requestBody` (with JSON schema), and `responses` (with JSON
schemas). The `example-*.json` files are the extracted inline examples from
the spec — useful as realistic test data or for verifying that Java records
cover all fields.

### Default path prefixes

The script filters paths starting with:

- `/repos/{owner}/{repo}` — all single-repository endpoints (~460 operations)
- `/orgs/{org}/repos` — list and create org repositories
- `/user/repos` — list and create authenticated-user repositories

