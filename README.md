# Harness Jenkins Plugin

A Jenkins plugin that integrates the [Harness CLI (`harness`)](https://github.com/harness/cli) into Jenkins pipelines and Freestyle jobs, enabling you to push artifacts to the Harness Artifact Registry directly from your CI builds.

> **Upgrading from 1.x:** version 2.0 replaces the retired `hc` CLI (harness/harness-cli) with the Harness CLI from [harness/cli](https://github.com/harness/cli). Command syntax changes (for example `hc 'artifact push generic my-registry app.jar --name app'` becomes `harness 'push artifact:generic my-registry/app app.jar'`), the pipeline step `hc` is renamed `harness`, and a pinned tool version must be a harness/cli release such as `3.13.0`. See [Upgrading from 1.x](#upgrading-from-1x).

---

## Prerequisites

- A Harness account (any tier)
- A Harness personal access token (`pat.…`), service account token (`sat.…`) or scoped token (`st.…`)

---

## Install and configure the plugin

1. Install the Harness Plugin. Go to **Manage Jenkins** → **Plugins**.<br><img src="docs/install-plugin.png" width="70%">
2. Configure your Harness CLI details. Go to **Manage Jenkins** → **System**.<br><img src="docs/configure-plugin.png" width="30%">
3. Configure Harness CLI as a tool in Jenkins. For more information, see [Configure Harness CLI as a tool](#configure-harness-cli-as-a-tool) section.

---

## Configure Harness CLI as a tool

To use Harness CLI in your pipeline jobs, configure it as a tool in Jenkins. Go to **Manage Jenkins** → **Tools**.


### Automatic installation from GitHub

If your agent has internet access, you can configure the installer to automatically download the Harness CLI
from [GitHub releases (harness/cli)](https://github.com/harness/cli/releases). The installer verifies the download's SHA-256 checksum and registers the bundled Artifact Registry plugin (`harness-har`) inside the tool directory, so it does not use or modify the agent user's `~/.harness`.


<img src="docs/automatic-installation.png" width="30%">

---

## Usage

### Step 1: Configure the Harness CLI tool installation

1. Go to **Manage Jenkins** → **Tools** and then navigate to **Harness CLI installations**.
2. Click **Add Harness CLI**. Provide a name (for example, `harness-cli`).
3. Enable **Install automatically**, and set the required **Version** (for example, `3.13.0`).

    The plugin downloads the binary from the [harness/cli](https://github.com/harness/cli/releases) GitHub releases automatically.

![Harness CLI tool installation](docs/screenshot-tools-installation.png)

> Note: To always install the latest release, leave the version field empty. Jenkins then checks GitHub for the latest release at most once an hour; pin a version to skip the check entirely.


### Step 2: Configure Harness credentials

Go to **Manage Jenkins** → **System** and then navigate to **Harness CLI Configuration**.

![Harness CLI system configuration](docs/screenshot-system-config.png)

| Field | Description |
|---|---|
| **API URL** | Harness API endpoint. For example, `https://app.harness.io`. |
| **API Token** | Personal access token, service account token or scoped token - stored as a Jenkins secret. |
| **Organization ID** | Optional. Your Harness Organization slug. For example, `default`. |
| **Project ID** | Optional. Your Harness Project slug. |

Every Harness CLI command run by the plugin receives these values as environment variables (`HARNESS_API_KEY`, `HARNESS_API_URL`, `HARNESS_ORG`, `HARNESS_PROJECT`). No credentials are written to the agent, so there is no login or logout step, builds on different agents need no extra setup, and concurrent builds on the same agent do not interfere with each other. API tokens are masked in the command line and output written to the build log.

#### Per-job credentials

A job can use its own credentials instead of the global ones. For each command, the first match wins:

1. `--profile <name>` in the command, or `HARNESS_PROFILE` set in the build environment: a profile saved earlier with `harness auth login`.
2. `HARNESS_API_KEY` set in the build environment. Set `HARNESS_API_URL`, `HARNESS_ORG` and `HARNESS_PROJECT` as needed too; the global values are not mixed in.3. The global **Harness CLI Configuration**.

The recommended way is a Jenkins **Secret text** credential bound with `withCredentials`, which Jenkins also masks in the log:

```groovy
withCredentials([string(credentialsId: 'harness-prod-token', variable: 'HARNESS_API_KEY')]) {
    withEnv(['HARNESS_API_URL=https://app.harness.io', 'HARNESS_ORG=default', 'HARNESS_PROJECT=my-project']) {
        harness 'push artifact:generic my-registry/app app.jar --version 1.0.0'
    }
}
```

#### Using `harness auth login` in a job

`harness auth login --api-url <url> --api-token <token>` works in a `harness` step, but the saved profile is only used by commands that name it, for example `harness 'push artifact:generic my-registry/app app.jar --profile default'`. Otherwise the global credentials still apply.

With the automatic installer, profiles are saved in a CLI home private to the job's workspace (`<workspace>@tmp/harness-cli-home`), so other jobs on the agent never see them. They do persist across builds of the same job until you run `harness auth logout` or the workspace is wiped. Pass the token from a credential rather than writing it in the Jenkinsfile.


### Step 3: Use in a Freestyle job

1. In a Freestyle job configuration, go to **Build Steps** → **Add build step** and then select **Run Harness CLI command**.

    <img src="docs/screenshot-add-build-step.png" width="50%">

2. Select the CLI installation (or leave as **Use harness from system PATH**) and type the command to run.
You may include or omit the leading `harness`. For example: `push artifact:rpm my-repo/mypackage /path/to/file.rpm`.

    ![Run Harness CLI command build step](docs/screenshot-build-step-config.png)

---

## Pipeline Examples

### Declarative Pipeline - push an artifact

```groovy
pipeline {
    agent any

    tools {
        harnessCli 'harness-cli'   // matches the name set in Manage Jenkins → Tools
    }

    stages {
        stage('Build') {
            steps {
                sh 'mvn clean package -DskipTests'
            }
        }

        stage('Push to Harness Artifact Registry') {
            steps {
                harness 'push artifact:generic my-registry/myapp target/myapp-1.0.jar --version 1.0.0'
            }
        }
    }
}
```

### Declarative Pipeline - multiple artifact types

```groovy
pipeline {
    agent any

    tools { harnessCli 'harness-cli' }

    stages {
        stage('Push RPM') {
            steps {
                harness 'push artifact:rpm rpm-repo/mypackage dist/mypackage-1.0.x86_64.rpm'
            }
        }

        stage('Push Debian package') {
            steps {
                harness 'push artifact:debian debian-repo dist/mypackage_1.0_amd64.deb --distribution bookworm --component main'
            }
        }
    }
}
```

### Scripted Pipeline

```groovy
node {
    tool name: 'harness-cli', type: 'io.jenkins.plugins.har.cli.HarnessCliInstallation'

    stage('Push') {
        harness 'push artifact:generic my-registry/output build/output.zip --version 1.0.0'
    }
}
```

The `harness` step runs any Harness CLI command, not only pushes. Pass the arguments without the leading `harness`.

### Available commands (examples)

| `harness` step argument | Description |
|---|---|
| `version` | Print installed CLI version |
| `auth status` | Check the credentials and scope in use |
| `push artifact:generic <registry>/<name> <file> --version <v>` | Push a generic artifact |
| `push artifact:rpm <registry>/<name> <file.rpm>` | Push an RPM package |
| `push artifact:debian <registry> <file.deb> --distribution <d> --component <c>` | Push a Debian package |
| `list artifact <registry>` | List artifacts in a registry |

> Note: Run `harness push --help` or see the [Harness CLI repository](https://github.com/harness/cli) for the full command reference.

---

## Upgrading from 1.x

Version 2.0 moves the plugin from the retired `hc` CLI ([harness/harness-cli](https://github.com/harness/harness-cli)) to the Harness CLI ([harness/cli](https://github.com/harness/cli)).

- **Command syntax:** rename `hc` steps to `harness` and update their arguments, and update the arguments of Freestyle build steps. For example, `artifact push generic my-registry app.jar --name app --version 1.0.0` becomes `push artifact:generic my-registry/app app.jar --version 1.0.0`. The `tools { harnessCli '…' }` directive does not change.
- **Pinned versions:** a pinned tool version must be a [harness/cli release](https://github.com/harness/cli/releases), for example `3.13.0`. The installer only downloads releases from that page, so any other version, such as `1.3.47`, is rejected. Leave the field empty to install the latest release. Agents reinstall automatically on the next build.
- **Authentication:** the plugin no longer runs `hc auth login` or `hc auth logout`. Credentials from **Manage Jenkins** → **System** are passed to each command as environment variables, unless the job supplies its own (see [Per-job credentials](#per-job-credentials)). Shell steps that call the CLI directly (for example `"$HARNESS_CLI_PATH/harness"`) do not receive the global credentials.

---

## Contributing

Refer to [CONTRIBUTING.md](https://github.com/harness/harness/blob/main/CONTRIBUTING.md).

## License

Apache License 2.0. Refer [LICENSE](https://github.com/harness/harness/blob/main/LICENSE).