# Kestra Plugin Devcontainer

This devcontainer provides a quick and easy setup for anyone using VSCode to get up and running quickly with plugin development for Kestra. It bootstraps a docker container for you to develop inside of without the need to manually setup the environment for developing plugins.

---

## INSTRUCTIONS

### Setup:

Once you have this repo cloned to your local system, you will need to install the VSCode extension [Remote Development](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.vscode-remote-extensionpack).

Then run the following command from the command palette:
`Dev Containers: Open Folder in Container...` and select your Kestra root folder.

This will then put you inside a docker container ready for development.

NOTE: you'll need to wait for the gradle build to finish and compile Java files but this process should happen automatically within VSCode.

---

### Development:

It is recommended to read the following plugin development guide so you can better understand the conventions this library follows: https://kestra.io/docs/plugin-developer-guide.

This repository is a plain Java library (see `AGENTS.md`), not a deployable Kestra plugin: it ships no task, no trigger, and no plugin jar, so there is no local Kestra instance to run or plugin folder to mount. Build and test it directly with Gradle from inside the devcontainer:

```bash
$ ./gradlew build
```

`Tests`:

```bash
$ ./gradlew check --parallel
```

---

### GIT

If you want to commit to GitHub, make sure to navigate to the `~/.ssh` folder and either create a new SSH key or override the existing `id_ed25519` file and paste an existing SSH key from your local machine into this file. You will then need to change the permissions of the file by running: `chmod 600 id_ed25519`. This will allow you to then push to GitHub.

---
