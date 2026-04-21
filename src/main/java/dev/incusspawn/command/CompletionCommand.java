package dev.incusspawn.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "completion",
        description = "Print shell completion script",
        mixinStandardHelpOptions = true
)
public class CompletionCommand implements Runnable {

    enum Shell { bash, zsh, fish }

    @Parameters(index = "0", description = "Shell type: bash, zsh, fish", arity = "0..1", defaultValue = "bash")
    Shell shell;

    @Option(names = "--install", description = "Print installation instructions instead of the script")
    boolean install;

    @Override
    public void run() {
        if (install) {
            printInstallInstructions();
            return;
        }
        switch (shell) {
            case zsh  -> System.out.println(ZSH_COMPLETION);
            case bash -> System.out.println(BASH_COMPLETION);
            case fish -> System.out.println(FISH_COMPLETION);
        }
    }

    private void printInstallInstructions() {
        System.out.println("""
                # ── Zsh ────────────────────────────────────────────────────────────────────
                # Option A: source directly from your ~/.zshrc
                #   eval "$(isx completion zsh)"
                #
                # Option B: save to a completion file (faster shell startup)
                #   mkdir -p ~/.zsh/completions
                #   isx completion zsh > ~/.zsh/completions/_isx
                #   echo 'fpath=(~/.zsh/completions $fpath)' >> ~/.zshrc
                #   echo 'autoload -Uz compinit && compinit' >> ~/.zshrc
                #
                # ── Bash ────────────────────────────────────────────────────────────────────
                # Option A: source directly from your ~/.bashrc
                #   eval "$(isx completion bash)"
                #
                # Option B: save to a completion file
                #   mkdir -p ~/.local/share/bash-completion/completions
                #   isx completion bash > ~/.local/share/bash-completion/completions/isx
                #
                # ── Fish ────────────────────────────────────────────────────────────────────
                # Save to the fish completions directory:
                #   mkdir -p ~/.config/fish/completions
                #   isx completion fish > ~/.config/fish/completions/isx.fish
                """);
    }

    // ── Zsh completion ──────────────────────────────────────────────────────────

    private static final String ZSH_COMPLETION = """
            #compdef isx

            _isx_instances() {
              local -a instances
              instances=(${(f)"$(isx instances 2>/dev/null)"})
              _describe -t instances 'instance' instances
            }

            _isx_template_names() {
              local -a templates
              templates=(${(f)"$(isx templates 2>/dev/null)"})
              _describe -t templates 'template' templates
            }

            _isx_templates() {
              local state line; typeset -A opt_args
              _arguments -C \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '1: :->subcmd' \\
                '*:: :->args'

              local -a _tpl_subcmds
              _tpl_subcmds=(
                'list:list available template names'
                'edit:edit a template definition'
                'new:create a new template definition'
              )

              case $state in
                subcmd)
                  _describe -t subcmds 'templates subcommand' _tpl_subcmds
                  _isx_template_names ;;
                args)
                  case $line[1] in
                    edit) _arguments '1:template:_isx_template_names' ;;
                    new)  _arguments '--project[Create in project-local directory]' '1:name' ;;
                    list) _arguments '(-v --verbose)'{-v,--verbose}'[Show source and description]' ;;
                  esac ;;
              esac
            }

            _isx_branch() {
              _arguments \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '--from=[Source instance to branch from]:instance:_isx_instances' \\
                '--gui[Enable GUI passthrough (Wayland + GPU + audio)]' \\
                '--airgap[Disable network access (complete isolation)]' \\
                '--proxy-only[Restrict network to host proxy only]' \\
                '--inbox=[Host directory to mount read-only at /home/agentuser/inbox]:directory:_files -/' \\
                '--cpu=[CPU core limit]:number' \\
                '--memory=[Memory limit, e.g. 8GB]:size' \\
                '--disk=[Disk size limit]:size' \\
                '--no-start[Don'"'"'t start the instance after creation]' \\
                '1:new instance name'
            }

            _isx_build() {
              _arguments \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '--all[Rebuild all defined templates]' \\
                '--missing[Build only templates that don'"'"'t exist yet]' \\
                '--vm[Build as a VM instead of a container]' \\
                '--yes[Skip interactive confirmations]' \\
                '1::template name:_isx_template_names'
            }

            _isx_destroy() {
              _arguments \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '--force[Force destruction, even for templates]' \\
                '1:environment name:_isx_instances'
            }

            _isx_list() {
              _arguments \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '--plain[Plain text output (no TUI)]'
            }

            _isx_shell() {
              _arguments \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '1:clone name:_isx_instances'
            }

            _isx_project() {
              local state line; typeset -A opt_args
              _arguments -C \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '1: :->subcmd' \\
                '*:: :->args'

              local -a _project_subcmds
              _project_subcmds=(
                'create:create a project template from a parent base image'
                'update:update a project template (system packages, git repos, dependencies)'
              )

              case $state in
                subcmd) _describe -t subcmds 'project subcommand' _project_subcmds ;;
                args)
                  case $line[1] in
                    create)
                      _arguments \\
                        '(-h --help)'{-h,--help}'[Show help]' \\
                        '--config=[Path to incus-spawn.yaml]:file:_files' \\
                        '1:project template name' ;;
                    update)
                      _arguments \\
                        '(-h --help)'{-h,--help}'[Show help]' \\
                        '--config=[Path to incus-spawn.yaml]:file:_files' \\
                        '1:project template name:_isx_instances' ;;
                  esac ;;
              esac
            }

            _isx_proxy() {
              local state line; typeset -A opt_args
              _arguments -C \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '1: :->subcmd' \\
                '*:: :->args'

              local -a _proxy_subcmds
              _proxy_subcmds=(
                'start:start the MITM authentication proxy'
                'stop:stop the proxy'
                'status:check if the proxy is running'
                'install:install the proxy as a systemd user service'
                'uninstall:stop and remove the systemd proxy service'
                'logs:follow the proxy log file in real time'
              )

              case $state in
                subcmd) _describe -t subcmds 'proxy subcommand' _proxy_subcmds ;;
                args)
                  case $line[1] in
                    start)
                      _arguments \\
                        '(-h --help)'{-h,--help}'[Show help]' \\
                        '--port=[MITM TLS proxy port]:port' \\
                        '--health-port=[Health check HTTP port]:port' ;;
                    stop|status|install|uninstall|logs)
                      _arguments '(-h --help)'{-h,--help}'[Show help]' ;;
                  esac ;;
              esac
            }

            _isx_completion() {
              _arguments \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '--install[Print installation instructions]' \\
                '1::shell:(bash zsh fish)'
            }

            _isx() {
              local context state state_descr line
              typeset -A opt_args

              _arguments -C \\
                '(-h --help)'{-h,--help}'[Show help and exit]' \\
                '(-V --version)'{-V,--version}'[Show version and exit]' \\
                '1: :->cmd' \\
                '*:: :->args'

              case $state in
                cmd)
                  local -a cmds
                  cmds=(
                    'init:one-time host setup (install Incus, configure auth)'
                    'build:build or rebuild a template image'
                    'project:manage project templates'
                    'branch:create a new instance from an existing one'
                    'shell:open a shell in an existing clone'
                    'list:list all incus-spawn environments'
                    'destroy:destroy a clone environment'
                    'update-all:update all templates (packages, git repos, dependencies)'
                    'proxy:manage the MITM authentication proxy'
                    'completion:print shell completion script'
                    'templates:manage template definitions'
                    'instances:list connectable instance names'
                  )
                  _describe -t commands 'isx command' cmds ;;
                args)
                  local cmd=$line[1]
                  (( CURRENT-- ))
                  shift words
                  case $cmd in
                    branch)     _isx_branch ;;
                    build)      _isx_build ;;
                    destroy)    _isx_destroy ;;
                    list)       _isx_list ;;
                    shell)      _isx_shell ;;
                    project)    _isx_project ;;
                    proxy)      _isx_proxy ;;
                    completion) _isx_completion ;;
                    templates)  _isx_templates ;;
                    instances)  _arguments '(-h --help)'{-h,--help}'[Show help]' ;;
                  esac ;;
              esac
            }

            compdef _isx isx
            """;

    // ── Bash completion ─────────────────────────────────────────────────────────

    private static final String BASH_COMPLETION = """
            # bash completion for isx (incus-spawn)

            _isx_list_instances() {
              isx instances 2>/dev/null
            }

            _isx_list_templates() {
              isx templates 2>/dev/null
            }

            _isx() {
              local cur prev words cword
              _init_completion || return

              local commands="init build project branch shell list destroy update-all proxy completion templates instances"

              # Determine which subcommand is active
              local cmd=""
              local i
              for (( i=1; i < cword; i++ )); do
                case "${words[i]}" in
                  init|build|project|branch|shell|list|destroy|update-all|proxy|completion|templates|instances)
                    cmd="${words[i]}"
                    break ;;
                esac
              done

              if [[ -z "$cmd" ]]; then
                # Complete top-level commands and options
                case "$cur" in
                  -*)
                    COMPREPLY=( $(compgen -W "--help --version" -- "$cur") )
                    ;;
                  *)
                    COMPREPLY=( $(compgen -W "$commands" -- "$cur") )
                    ;;
                esac
                return
              fi

              case "$cmd" in
                branch)
                  case "$prev" in
                    --from)
                      COMPREPLY=( $(compgen -W "$(_isx_list_instances)" -- "$cur") )
                      return ;;
                    --inbox)
                      _filedir -d
                      return ;;
                    --cpu|--memory|--disk) return ;;
                  esac
                  COMPREPLY=( $(compgen -W "--help --from --gui --airgap --proxy-only --inbox --cpu --memory --disk --no-start" -- "$cur") )
                  ;;
                build)
                  case "$prev" in
                    build)
                      COMPREPLY=( $(compgen -W "$(_isx_list_templates) --help --all --missing --vm --yes" -- "$cur") )
                      return ;;
                  esac
                  COMPREPLY=( $(compgen -W "--help --all --missing --vm --yes" -- "$cur") )
                  ;;
                destroy)
                  case "$prev" in
                    destroy)
                      COMPREPLY=( $(compgen -W "$(_isx_list_instances) --help --force" -- "$cur") )
                      return ;;
                  esac
                  COMPREPLY=( $(compgen -W "--help --force" -- "$cur") )
                  ;;
                list)
                  COMPREPLY=( $(compgen -W "--help --plain" -- "$cur") )
                  ;;
                shell)
                  case "$prev" in
                    shell)
                      COMPREPLY=( $(compgen -W "$(_isx_list_instances) --help" -- "$cur") )
                      return ;;
                  esac
                  COMPREPLY=( $(compgen -W "--help" -- "$cur") )
                  ;;
                project)
                  local proj_subcmds="create update"
                  local proj_cmd=""
                  local j
                  for (( j=i+1; j < cword; j++ )); do
                    case "${words[j]}" in
                      create|update) proj_cmd="${words[j]}"; break ;;
                    esac
                  done
                  if [[ -z "$proj_cmd" ]]; then
                    COMPREPLY=( $(compgen -W "$proj_subcmds --help" -- "$cur") )
                  else
                    case "$proj_cmd" in
                      create) COMPREPLY=( $(compgen -W "--help --config" -- "$cur") ) ;;
                      update)
                        case "$prev" in
                          update)
                            COMPREPLY=( $(compgen -W "$(_isx_list_instances) --help --config" -- "$cur") )
                            return ;;
                        esac
                        COMPREPLY=( $(compgen -W "--help --config" -- "$cur") )
                        ;;
                    esac
                  fi
                  ;;
                proxy)
                  local proxy_subcmds="start stop status install uninstall logs"
                  local proxy_cmd=""
                  local j
                  for (( j=i+1; j < cword; j++ )); do
                    case "${words[j]}" in
                      start|stop|status|install|uninstall|logs) proxy_cmd="${words[j]}"; break ;;
                    esac
                  done
                  if [[ -z "$proxy_cmd" ]]; then
                    COMPREPLY=( $(compgen -W "$proxy_subcmds --help" -- "$cur") )
                  else
                    case "$proxy_cmd" in
                      start) COMPREPLY=( $(compgen -W "--help --port --health-port" -- "$cur") ) ;;
                      *) COMPREPLY=( $(compgen -W "--help" -- "$cur") ) ;;
                    esac
                  fi
                  ;;
                completion)
                  case "$prev" in
                    completion)
                      COMPREPLY=( $(compgen -W "bash zsh fish --help --install" -- "$cur") )
                      return ;;
                  esac
                  COMPREPLY=( $(compgen -W "--help --install" -- "$cur") )
                  ;;
                templates)
                  local tpl_subcmds="list edit new"
                  local tpl_cmd=""
                  local j
                  for (( j=i+1; j < cword; j++ )); do
                    case "${words[j]}" in
                      list|edit|new) tpl_cmd="${words[j]}"; break ;;
                    esac
                  done
                  if [[ -z "$tpl_cmd" ]]; then
                    COMPREPLY=( $(compgen -W "$tpl_subcmds $(_isx_list_templates) --help" -- "$cur") )
                  else
                    case "$tpl_cmd" in
                      list) COMPREPLY=( $(compgen -W "--help --verbose -v" -- "$cur") ) ;;
                      edit)
                        case "$prev" in
                          edit)
                            COMPREPLY=( $(compgen -W "$(_isx_list_templates) --help" -- "$cur") )
                            return ;;
                        esac
                        COMPREPLY=( $(compgen -W "--help" -- "$cur") )
                        ;;
                      new) COMPREPLY=( $(compgen -W "--help --project" -- "$cur") ) ;;
                    esac
                  fi
                  ;;
                init|update-all|instances)
                  COMPREPLY=( $(compgen -W "--help" -- "$cur") )
                  ;;
              esac
            }

            complete -F _isx isx
            """;

    // ── Fish completion ─────────────────────────────────────────────────────────

    private static final String FISH_COMPLETION = """
            # fish completion for isx (incus-spawn)

            # Helper: list connectable instances (excludes templates)
            function __isx_instances
              isx instances 2>/dev/null
            end

            # Helper: list available template definitions
            function __isx_templates
              isx templates 2>/dev/null
            end

            # Helper: true when no subcommand has been typed yet
            function __isx_no_subcommand
              not string match -qr -- '^(init|build|project|branch|shell|list|destroy|update-all|proxy|completion|templates|instances)$' (commandline -opc)[2..-1]
            end

            # Helper: true when a specific subcommand is active
            function __isx_using_subcommand
              string match -qr -- "\\b$argv[1]\\b" (commandline -opc)
            end

            # ── Top-level commands ───────────────────────────────────────────────────────

            complete -c isx -f -n __isx_no_subcommand -a init         -d 'One-time host setup (install Incus, configure auth)'
            complete -c isx -f -n __isx_no_subcommand -a build        -d 'Build or rebuild a template image'
            complete -c isx -f -n __isx_no_subcommand -a project      -d 'Manage project templates'
            complete -c isx -f -n __isx_no_subcommand -a branch       -d 'Create a new instance from an existing one'
            complete -c isx -f -n __isx_no_subcommand -a shell        -d 'Open a shell in an existing clone'
            complete -c isx -f -n __isx_no_subcommand -a list         -d 'List all incus-spawn environments'
            complete -c isx -f -n __isx_no_subcommand -a destroy      -d 'Destroy a clone environment'
            complete -c isx -f -n __isx_no_subcommand -a update-all   -d 'Update all templates (packages, git repos, dependencies)'
            complete -c isx -f -n __isx_no_subcommand -a proxy        -d 'Manage the MITM authentication proxy'
            complete -c isx -f -n __isx_no_subcommand -a completion   -d 'Print shell completion script'
            complete -c isx -f -n __isx_no_subcommand -a templates    -d 'Manage template definitions'
            complete -c isx -f -n __isx_no_subcommand -a instances    -d 'List connectable instance names'

            # ── branch ───────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand branch' -a '(__isx_instances)' -d 'Instance name'
            complete -c isx -f -n '__isx_using_subcommand branch' -l from        -d 'Source instance to branch from' -a '(__isx_instances)'
            complete -c isx -f -n '__isx_using_subcommand branch' -l gui         -d 'Enable GUI passthrough (Wayland + GPU + audio)'
            complete -c isx -f -n '__isx_using_subcommand branch' -l airgap      -d 'Disable network access (complete isolation)'
            complete -c isx -f -n '__isx_using_subcommand branch' -l proxy-only  -d 'Restrict network to host proxy only'
            complete -c isx -F -n '__isx_using_subcommand branch' -l inbox       -d 'Host directory to mount read-only at /home/agentuser/inbox'
            complete -c isx -f -n '__isx_using_subcommand branch' -l cpu         -d 'CPU core limit'
            complete -c isx -f -n '__isx_using_subcommand branch' -l memory      -d 'Memory limit, e.g. 8GB'
            complete -c isx -f -n '__isx_using_subcommand branch' -l disk        -d 'Disk size limit'
            complete -c isx -f -n '__isx_using_subcommand branch' -l no-start    -d "Don't start the instance after creation"

            # ── build ────────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand build' -a '(__isx_templates)' -d 'Template name'
            complete -c isx -f -n '__isx_using_subcommand build' -l all     -d 'Rebuild all defined templates'
            complete -c isx -f -n '__isx_using_subcommand build' -l missing -d 'Build only templates that don'"'"'t exist yet'
            complete -c isx -f -n '__isx_using_subcommand build' -l vm      -d 'Build as a VM instead of a container'
            complete -c isx -f -n '__isx_using_subcommand build' -l yes     -d 'Skip interactive confirmations'

            # ── destroy ──────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand destroy' -a '(__isx_instances)' -d 'Environment name'
            complete -c isx -f -n '__isx_using_subcommand destroy' -l force -d 'Force destruction, even for templates'

            # ── list ─────────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand list' -l plain -d 'Plain text output (no TUI)'

            # ── shell ────────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand shell' -a '(__isx_instances)' -d 'Clone name'

            # ── project ──────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand project; and not string match -qr -- "\\b(create|update)\\b" (commandline -opc)' -a create -d 'Create a project template from a parent base image'
            complete -c isx -f -n '__isx_using_subcommand project; and not string match -qr -- "\\b(create|update)\\b" (commandline -opc)' -a update -d 'Update a project template'

            complete -c isx -F -n '__isx_using_subcommand project; and __isx_using_subcommand create' -l config -d 'Path to incus-spawn.yaml'
            complete -c isx -F -n '__isx_using_subcommand project; and __isx_using_subcommand update' -l config -d 'Path to incus-spawn.yaml'
            complete -c isx -f -n '__isx_using_subcommand project; and __isx_using_subcommand update' -a '(__isx_instances)' -d 'Project template name'

            # ── templates ────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand templates; and not string match -qr -- "\\b(list|edit|new)\\b" (commandline -opc)' -a list -d 'List available template names'
            complete -c isx -f -n '__isx_using_subcommand templates; and not string match -qr -- "\\b(list|edit|new)\\b" (commandline -opc)' -a edit -d 'Edit a template definition'
            complete -c isx -f -n '__isx_using_subcommand templates; and not string match -qr -- "\\b(list|edit|new)\\b" (commandline -opc)' -a new  -d 'Create a new template definition'
            complete -c isx -f -n '__isx_using_subcommand templates; and not string match -qr -- "\\b(list|edit|new)\\b" (commandline -opc)' -a '(__isx_templates)' -d 'Template name'

            complete -c isx -f -n '__isx_using_subcommand templates; and __isx_using_subcommand list' -s v -l verbose -d 'Show source and description'
            complete -c isx -f -n '__isx_using_subcommand templates; and __isx_using_subcommand edit' -a '(__isx_templates)' -d 'Template name'
            complete -c isx -f -n '__isx_using_subcommand templates; and __isx_using_subcommand new' -l project -d 'Create in project-local directory'

            # ── proxy ────────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand proxy; and not string match -qr -- "\\b(start|stop|status|install|uninstall|logs)\\b" (commandline -opc)' -a start     -d 'Start the MITM authentication proxy'
            complete -c isx -f -n '__isx_using_subcommand proxy; and not string match -qr -- "\\b(start|stop|status|install|uninstall|logs)\\b" (commandline -opc)' -a stop      -d 'Stop the proxy'
            complete -c isx -f -n '__isx_using_subcommand proxy; and not string match -qr -- "\\b(start|stop|status|install|uninstall|logs)\\b" (commandline -opc)' -a status    -d 'Check if the proxy is running'
            complete -c isx -f -n '__isx_using_subcommand proxy; and not string match -qr -- "\\b(start|stop|status|install|uninstall|logs)\\b" (commandline -opc)' -a install   -d 'Install the proxy as a systemd user service'
            complete -c isx -f -n '__isx_using_subcommand proxy; and not string match -qr -- "\\b(start|stop|status|install|uninstall|logs)\\b" (commandline -opc)' -a uninstall -d 'Stop and remove the systemd proxy service'
            complete -c isx -f -n '__isx_using_subcommand proxy; and not string match -qr -- "\\b(start|stop|status|install|uninstall|logs)\\b" (commandline -opc)' -a logs      -d 'Follow the proxy log file in real time'

            complete -c isx -f -n '__isx_using_subcommand proxy; and __isx_using_subcommand start' -l port        -d 'MITM TLS proxy port'
            complete -c isx -f -n '__isx_using_subcommand proxy; and __isx_using_subcommand start' -l health-port -d 'Health check HTTP port'

            # ── completion ───────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand completion' -a 'bash zsh fish' -d 'Shell type'
            complete -c isx -f -n '__isx_using_subcommand completion' -l install -d 'Print installation instructions'
            """;
}
