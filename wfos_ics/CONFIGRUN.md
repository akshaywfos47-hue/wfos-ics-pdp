## Configuration Service

Before using the Configuration Service, ensure that all CSW services are running.

### Login

Login before performing any Configuration Service operations.

```bash
    csw-config-cli login
```

Use the following credentials:

- **Username:** `config-admin1`
- **Password:** `config-admin1`

### Upload a Configuration File

Upload the RGRIP lookup table to the Configuration Service using:

```bash
      csw-config-cli create /wfos/rgriphcd/RGRIP_Lookup_Table_Angles.xlsx \
-i config-files/wfos-rgriphcd/RGRIP_Lookup_Table_Angles.xlsx \
--annex \
-c "Initial RGRIP lookup table"
```

**Command options**

- `/wfos/rgriphcd/RGRIP_Lookup_Table_Angles.xlsx` - Destination path in the Configuration Service.
- `-i` - Path to the local configuration file.
- `--annex` - Stores the binary file in the annex repository.
- `-c` - Commit message for the uploaded version.

### Update a Configuration File

If the configuration file already exists in the Configuration Service, upload a new version using the `update` command.

```bash
    csw-config-cli update /wfos/rgriphcd/RGRIP_Lookup_Table_Angles.xlsx \
-i config-files/wfos-rgriphcd/RGRIP_Lookup_Table_Angles.xlsx \
-c "Updated RGRIP lookup table"
```

**Command options**

- `/wfos/rgriphcd/RGRIP_Lookup_Table_Angles.xlsx` - Path of the existing configuration file in the Configuration Service.
- `-i` - Path to the updated local configuration file.
- `-c` - Commit message describing the changes.

**Note**

- Use `create` only for the initial upload of a configuration file.
- Use `update` to upload a new version of an existing configuration file.
- Each successful update creates a new version while preserving the complete version history.

### View Configuration History

Display all available versions of a configuration file stored in the Configuration Service.

```bash
    csw-config-cli history /wfos/rgriphcd/RGRIP_Lookup_Table_Angles.xlsx
```

The history includes information such as:

- Version ID
- Commit message
- Author
- Timestamp
---

### Get the Active Version

Display the version ID of the currently active configuration file.

```bash
    csw-config-cli getActiveVersion \
/wfos/rgriphcd/RGRIP_Lookup_Table_Angles.xlsx
```

---

### Set the Active Version

Set a specific version of the configuration file as the active version.

```bash
    csw-config-cli setActiveVersion \
/wfos/rgriphcd/RGRIP_Lookup_Table_Angles.xlsx \
--id 2 \
-c "Rollback to version 2"
```

**Command options**

- `/wfos/rgriphcd/RGRIP_Lookup_Table_Angles.xlsx` - Path of the configuration file in the Configuration Service.
- `--id` - Version ID to be set as the active version.
- `-c` - Commit message describing the reason for changing the active version.

**Note**

- Setting an active version does not delete any existing versions.
- The selected version becomes the active version that clients receive when requesting the active configuration.
- This command is useful for rolling back to a previous version of the configuration file.

### Download the Active Version

Download the latest active version of the configuration file.

```bash
    csw-config-cli getActive /wfos/rgriphcd/RGRIP_Lookup_Table_Angles.xlsx \
    -o RGRIP_Lookup_Table_Angles.xlsx
```

### List Available Configuration Files

```bash
    csw-config-cli list
```

### View Configuration Metadata

```bash
   csw-config-cli getMetadata
```

