package dev.incusspawn.command;

import dev.incusspawn.Platform;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

@CommandDefinition(
        name = "completion",
        description = "Print shell completion script",
        generateHelp = true
)
public class CompletionCommand extends BaseCommand {

    enum Shell { bash, zsh, fish }

    @Argument(description = "Shell type: bash, zsh, fish", required = false, defaultValue = {"bash"})
    Shell shell;

    @Option(name = "install", description = "Print installation instructions instead of the script", hasValue = false)
    boolean install;

    @Override
    protected CommandResult doExecute() throws Exception {
        if (install) {
            printInstallInstructions();
            return CommandResult.SUCCESS;
        }
        var script = rawScript(shell);
        // The `vm` appliance command only exists on macOS (Incus runs natively on Linux), so it is
        // not registered in the Linux command tree — keep the completion script in step with that.
        if (!Platform.isMacOS()) {
            script = stripVmCommand(script, shell);
        }
        System.out.println(script);
        return CommandResult.SUCCESS;
    }

    /** The full (macOS) completion script for a shell, before any platform-specific filtering. */
    static String rawScript(Shell shell) {
        return switch (shell) {
            case zsh  -> ZSH_COMPLETION;
            case bash -> BASH_COMPLETION;
            case fish -> FISH_COMPLETION;
        };
    }

    /**
     * Remove the macOS-only {@code vm} command from a generated completion script so it is not
     * offered on Linux, matching the platform-specific command tree in {@code IncusSpawn}.
     * Removes the top-level {@code vm} suggestion, drops {@code vm} from the recognized
     * subcommand lists, and deletes the now-unreachable per-{@code vm} dispatch blocks
     * (the zsh {@code _isx_vm} function and the bash {@code vm)} case) so no dead code remains.
     */
    static String stripVmCommand(String script, Shell shell) {
        return switch (shell) {
            case zsh -> script
                    .replaceAll("(?m)^\\s*'vm:manage the incus-spawn VM appliance'\\R", "")
                    .replaceAll("(?m)^\\s*vm\\)\\s*_isx_vm ;;\\R", "")
                    // Drop the unreachable _isx_vm() function (ends at the first standalone brace).
                    .replaceAll("(?sm)^[ \\t]*_isx_vm\\(\\) \\{.*?^[ \\t]*\\}\\R", "");
            case bash -> script
                    .replace("tools vm doctor", "tools doctor")
                    .replace("tools|vm|doctor", "tools|doctor")
                    // Drop the unreachable vm) case (ends at the first standalone ";;").
                    .replaceAll("(?sm)^[ \\t]*vm\\)\\R.*?^[ \\t]*;;\\R", "");
            case fish -> script
                    .replace("tools|vm|doctor", "tools|doctor")
                    .replaceAll("(?m)^.*-n __isx_no_subcommand -a vm .*\\R", "")
                    .replaceAll("(?m)^\\s*# ── vm ─.*\\R", "")
                    .replaceAll("(?m)^.*__isx_using_subcommand vm.*\\R", "");
        };
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

            _isx_tool_names() {
              local -a tools
              tools=(${(f)"$(isx tools list 2>/dev/null)"})
              _describe -t tools 'tool' tools
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
                '--kvm[Expose /dev/kvm for nested virtualization]' \\
                '--no-kvm[Disable KVM even if the template was built with type: kvm]' \\
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
                '--out-of-sync[Rebuild templates that are out of sync]' \\
                '--with-parents[Rebuild the template and all its parents]' \\
                '--with-descendants[Rebuild the template and all templates inheriting from it]' \\
                '--missing[Build only templates that don'"'"'t exist yet]' \\
                '--type=[Instance type (overrides image definition)]:type:(container vm kvm)' \\
                '--yes[Skip interactive confirmations]' \\
                '1::template name:_isx_template_names'
            }

            _isx_destroy() {
              _arguments \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '--all-templates[Destroy all built templates (reverse order, derived first)]' \\
                '--all-instances[Destroy all instances]' \\
                '--skip-confirmation[Skip the confirmation prompt]' \\
                '1::environment name:_isx_instances'
            }

            _isx_reset() {
              _arguments \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '--skip-confirmation[Skip the confirmation prompt]'
            }

            _isx_list() {
              _arguments \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '--plain[Deprecated: plain output is the default for list (no-op)]'
            }

            _isx_shell() {
              _arguments \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '1:clone name:_isx_instances'
            }

            _isx_run() {
              _arguments \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '--action=[Action to run (tool-name or tool-name\\:action-id)]:action' \\
                '1:instance name:_isx_instances'
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
                'restart:restart the proxy service'
                'status:check if the proxy is running'
                'install:install the proxy as a systemd user service'
                'uninstall:stop and remove the systemd proxy service'
                'logs:follow the proxy log file in real time'
                'dump:run a local pass-through proxy to capture host-side API traffic'
              )

              case $state in
                subcmd) _describe -t subcmds 'proxy subcommand' _proxy_subcmds ;;
                args)
                  case $line[1] in
                    start)
                      _arguments \\
                        '(-h --help)'{-h,--help}'[Show help]' \\
                        '--port=[MITM TLS proxy port]:port' \\
                        '--health-port=[Health check HTTP port]:port' \\
                        '--gateway-ip=[Incus bridge gateway IP (skips Incus API lookup)]:ip' \\
                        '--debug[Log full API request/response details for traffic inspection]' ;;
                    dump)
                      _arguments \\
                        '(-h --help)'{-h,--help}'[Show help]' \\
                        '--port=[Local HTTP port]:port' ;;
                    stop|restart|status|install|uninstall|logs)
                      _arguments '(-h --help)'{-h,--help}'[Show help]' ;;
                  esac ;;
              esac
            }

            _isx_clean() {
              local state line; typeset -A opt_args
              _arguments -C \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '1: :->subcmd' \\
                '*:: :->args'

              local -a _clean_subcmds
              _clean_subcmds=(
                'cache:remove cached downloads, registry blobs, and build caches'
                'state:remove VM state, logs, and appliance artifacts'
                'config:remove configuration, SSH keys, and CA certificate'
                'pool:reclaim space from the storage pool'
                'all:remove all incus-spawn data'
              )

              case $state in
                subcmd) _describe -t subcmds 'clean subcommand' _clean_subcmds ;;
                args)
                  _arguments \\
                    '(-h --help)'{-h,--help}'[Show help]' \\
                    '--dry-run[Show what would be deleted without deleting]' \\
                    '--skip-confirmation[Skip the confirmation prompt]' ;;
              esac
            }

            _isx_vm() {
              local state line; typeset -A opt_args
              _arguments -C \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '1: :->subcmd' \\
                '*:: :->args'

              local -a _vm_subcmds
              _vm_subcmds=(
                'start:start the VM (creates disk image on first run)'
                'stop:stop the VM (graceful shutdown)'
                'restart:stop and restart the VM (applies pending appliance updates)'
                'status:show VM status and system diagnostics'
                'resize:grow the VM data disk that backs the storage pool'
                'console:follow VM serial console output'
              )

              case $state in
                subcmd) _describe -t subcmds 'vm subcommand' _vm_subcmds ;;
                args)
                  _arguments '(-h --help)'{-h,--help}'[Show help]' ;;
              esac
            }

            _isx_update_base() {
              _arguments \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '--list[List available versions]' \\
                '--latest[Track the latest version (remove any pin)]' \\
                '1::release tag'
            }

            _isx_doctor() {
              _arguments \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '--bundle[Collect findings and logs into a support archive]' \\
                '--deep[Run per-instance checks (DNS, TLS, resolv.conf)]'
            }

            _isx_tools() {
              local state line; typeset -A opt_args
              _arguments -C \\
                '(-h --help)'{-h,--help}'[Show help]' \\
                '1: :->subcmd' \\
                '*:: :->args'

              local -a _tools_subcmds
              _tools_subcmds=(
                'list:list available tools'
                'show:show details of a tool definition'
              )

              case $state in
                subcmd)
                  _describe -t subcmds 'tools subcommand' _tools_subcmds ;;
                args)
                  case $line[1] in
                    list) _arguments '(-v --verbose)'{-v,--verbose}'[Show source and description]' ;;
                    show) _arguments '1:tool name:_isx_tool_names' ;;
                  esac ;;
              esac
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
                    'clean:remove cached data, state, or configuration'
                    'project:manage project templates'
                    'branch:create a new instance from an existing one'
                    'shell:open a shell in an existing clone'
                    'run:run the default action or a specific action on an instance'
                    'list:list all incus-spawn environments'
                    'destroy:destroy a clone environment'
                    'reset:return to a freshly-installed state'
                    'update-all:update all templates (packages, git repos, dependencies)'
                    'proxy:manage the MITM authentication proxy'
                    'templates:manage template definitions'
                    'tools:list and inspect available tool definitions'
                    'vm:manage the incus-spawn VM appliance'
                    'update-base:check for and install base image updates'
                    'doctor:run health checks and offer to fix problems'
                    'help:AI-powered help — ask any question about incus-spawn'
                  )
                  _describe -t commands 'isx command' cmds ;;
                args)
                  local cmd=$line[1]
                  (( CURRENT-- ))
                  shift words
                  case $cmd in
                    branch)     _isx_branch ;;
                    build)      _isx_build ;;
                    clean)      _isx_clean ;;
                    destroy)    _isx_destroy ;;
                    reset)      _isx_reset ;;
                    doctor)     _isx_doctor ;;
                    help)       _arguments '(-h --help)'{-h,--help}'[Show help]' '--with-templates[Include template definitions in AI context]' '*:question' ;;
                    list)       _isx_list ;;
                    shell)      _isx_shell ;;
                    run)        _isx_run ;;
                    project)    _isx_project ;;
                    proxy)      _isx_proxy ;;
                    templates)  _isx_templates ;;
                    tools)      _isx_tools ;;
                    vm)         _isx_vm ;;
                    update-base) _isx_update_base ;;
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

            _isx_list_tools() {
              isx tools list 2>/dev/null
            }

            _isx() {
              local cur prev words cword
              _init_completion || return

              local commands="init build clean project branch shell run list destroy reset update-all update-base proxy templates tools vm doctor help"

              # Determine which subcommand is active
              local cmd=""
              local i
              for (( i=1; i < cword; i++ )); do
                case "${words[i]}" in
                  init|build|clean|project|branch|shell|run|list|destroy|reset|update-all|update-base|proxy|templates|tools|vm|doctor|help)
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
                  COMPREPLY=( $(compgen -W "--help --from --gui --kvm --no-kvm --airgap --proxy-only --inbox --cpu --memory --disk --no-start" -- "$cur") )
                  ;;
                build)
                  case "$prev" in
                    build)
                      COMPREPLY=( $(compgen -W "$(_isx_list_templates) --help --all --out-of-sync --with-parents --with-descendants --missing --type --yes" -- "$cur") )
                      return ;;
                    --type)
                      COMPREPLY=( $(compgen -W "container vm kvm" -- "$cur") )
                      return ;;
                  esac
                  COMPREPLY=( $(compgen -W "--help --all --out-of-sync --with-parents --with-descendants --missing --type --yes" -- "$cur") )
                  ;;
                clean)
                  local clean_subcmds="cache state config pool all"
                  local clean_cmd=""
                  local j
                  for (( j=i+1; j < cword; j++ )); do
                    case "${words[j]}" in
                      cache|state|config|pool|all) clean_cmd="${words[j]}"; break ;;
                    esac
                  done
                  if [[ -z "$clean_cmd" ]]; then
                    COMPREPLY=( $(compgen -W "$clean_subcmds --help" -- "$cur") )
                  else
                    COMPREPLY=( $(compgen -W "--help --dry-run --skip-confirmation" -- "$cur") )
                  fi
                  ;;
                destroy)
                  case "$prev" in
                    destroy)
                      COMPREPLY=( $(compgen -W "$(_isx_list_instances) --help --all-templates --all-instances --skip-confirmation" -- "$cur") )
                      return ;;
                  esac
                  COMPREPLY=( $(compgen -W "--help --all-templates --all-instances --skip-confirmation" -- "$cur") )
                  ;;
                reset)
                  COMPREPLY=( $(compgen -W "--help --skip-confirmation" -- "$cur") )
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
                run)
                  case "$prev" in
                    run)
                      COMPREPLY=( $(compgen -W "$(_isx_list_instances) --help --action" -- "$cur") )
                      return ;;
                    --action) return ;;
                  esac
                  COMPREPLY=( $(compgen -W "--help --action" -- "$cur") )
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
                  local proxy_subcmds="start stop restart status install uninstall logs dump"
                  local proxy_cmd=""
                  local j
                  for (( j=i+1; j < cword; j++ )); do
                    case "${words[j]}" in
                      start|stop|restart|status|install|uninstall|logs|dump) proxy_cmd="${words[j]}"; break ;;
                    esac
                  done
                  if [[ -z "$proxy_cmd" ]]; then
                    COMPREPLY=( $(compgen -W "$proxy_subcmds --help" -- "$cur") )
                  else
                    case "$proxy_cmd" in
                      start) COMPREPLY=( $(compgen -W "--help --port --health-port --gateway-ip --debug" -- "$cur") ) ;;
                      dump) COMPREPLY=( $(compgen -W "--help --port" -- "$cur") ) ;;
                      *) COMPREPLY=( $(compgen -W "--help" -- "$cur") ) ;;
                    esac
                  fi
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
                tools)
                  local tools_subcmds="list show"
                  local tools_cmd=""
                  local j
                  for (( j=i+1; j < cword; j++ )); do
                    case "${words[j]}" in
                      list|show) tools_cmd="${words[j]}"; break ;;
                    esac
                  done
                  if [[ -z "$tools_cmd" ]]; then
                    COMPREPLY=( $(compgen -W "$tools_subcmds --help" -- "$cur") )
                  else
                    case "$tools_cmd" in
                      list) COMPREPLY=( $(compgen -W "--help --verbose -v" -- "$cur") ) ;;
                      show)
                        case "$prev" in
                          show)
                            COMPREPLY=( $(compgen -W "$(_isx_list_tools) --help" -- "$cur") )
                            return ;;
                        esac
                        COMPREPLY=( $(compgen -W "--help" -- "$cur") )
                        ;;
                    esac
                  fi
                  ;;
                vm)
                  local vm_subcmds="start stop restart status resize console"
                  local vm_cmd=""
                  local j
                  for (( j=i+1; j < cword; j++ )); do
                    case "${words[j]}" in
                      start|stop|restart|status|resize|console) vm_cmd="${words[j]}"; break ;;
                    esac
                  done
                  if [[ -z "$vm_cmd" ]]; then
                    COMPREPLY=( $(compgen -W "$vm_subcmds --help" -- "$cur") )
                  else
                    COMPREPLY=( $(compgen -W "--help" -- "$cur") )
                  fi
                  ;;
                update-base)
                  COMPREPLY=( $(compgen -W "--help --list --latest" -- "$cur") )
                  ;;
                doctor)
                  COMPREPLY=( $(compgen -W "--help --bundle --deep" -- "$cur") )
                  ;;
                help)
                  COMPREPLY=( $(compgen -W "--help --with-templates" -- "$cur") )
                  ;;
                init|update-all)
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

            # Helper: list available tool definitions
            function __isx_tools
              isx tools list 2>/dev/null
            end

            # Helper: true when no subcommand has been typed yet
            function __isx_no_subcommand
              not string match -qr -- '^(init|build|clean|project|branch|shell|run|list|destroy|reset|update-all|update-base|proxy|templates|tools|vm|doctor|help)$' (commandline -opc)[2..-1]
            end

            # Helper: true when a specific subcommand is active
            function __isx_using_subcommand
              string match -qr -- "\\b$argv[1]\\b" (commandline -opc)
            end

            # ── Top-level commands ───────────────────────────────────────────────────────

            complete -c isx -f -n __isx_no_subcommand -a init         -d 'One-time host setup (install Incus, configure auth)'
            complete -c isx -f -n __isx_no_subcommand -a build        -d 'Build or rebuild a template image'
            complete -c isx -f -n __isx_no_subcommand -a clean        -d 'Remove cached data, state, or configuration'
            complete -c isx -f -n __isx_no_subcommand -a project      -d 'Manage project templates'
            complete -c isx -f -n __isx_no_subcommand -a branch       -d 'Create a new instance from an existing one'
            complete -c isx -f -n __isx_no_subcommand -a shell        -d 'Open a shell in an existing clone'
            complete -c isx -f -n __isx_no_subcommand -a run          -d 'Run the default action or a specific action on an instance'
            complete -c isx -f -n __isx_no_subcommand -a list         -d 'List all incus-spawn environments'
            complete -c isx -f -n __isx_no_subcommand -a destroy      -d 'Destroy a clone environment'
            complete -c isx -f -n __isx_no_subcommand -a reset        -d 'Return to a freshly-installed state'
            complete -c isx -f -n __isx_no_subcommand -a update-all   -d 'Update all templates (packages, git repos, dependencies)'
            complete -c isx -f -n __isx_no_subcommand -a proxy        -d 'Manage the MITM authentication proxy'
            complete -c isx -f -n __isx_no_subcommand -a templates    -d 'Manage template definitions'
            complete -c isx -f -n __isx_no_subcommand -a tools        -d 'List and inspect available tool definitions'
            complete -c isx -f -n __isx_no_subcommand -a vm              -d 'Manage the incus-spawn VM appliance'
            complete -c isx -f -n __isx_no_subcommand -a update-base     -d 'Check for and install base image updates'
            complete -c isx -f -n __isx_no_subcommand -a doctor          -d 'Run health checks and offer to fix problems'
            complete -c isx -f -n __isx_no_subcommand -a help            -d 'AI-powered help — ask any question about incus-spawn'

            # ── help ────────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand help' -l with-templates -d 'Include template definitions in AI context'

            # ── branch ───────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand branch' -a '(__isx_instances)' -d 'Instance name'
            complete -c isx -f -n '__isx_using_subcommand branch' -l from        -d 'Source instance to branch from' -a '(__isx_instances)'
            complete -c isx -f -n '__isx_using_subcommand branch' -l gui         -d 'Enable GUI passthrough (Wayland + GPU + audio)'
            complete -c isx -f -n '__isx_using_subcommand branch' -l kvm         -d 'Expose /dev/kvm for nested virtualization'
            complete -c isx -f -n '__isx_using_subcommand branch' -l no-kvm      -d 'Disable KVM even if the template was built with type: kvm'
            complete -c isx -f -n '__isx_using_subcommand branch' -l airgap      -d 'Disable network access (complete isolation)'
            complete -c isx -f -n '__isx_using_subcommand branch' -l proxy-only  -d 'Restrict network to host proxy only'
            complete -c isx -F -n '__isx_using_subcommand branch' -l inbox       -d 'Host directory to mount read-only at /home/agentuser/inbox'
            complete -c isx -f -n '__isx_using_subcommand branch' -l cpu         -d 'CPU core limit'
            complete -c isx -f -n '__isx_using_subcommand branch' -l memory      -d 'Memory limit, e.g. 8GB'
            complete -c isx -f -n '__isx_using_subcommand branch' -l disk        -d 'Disk size limit'
            complete -c isx -f -n '__isx_using_subcommand branch' -l no-start    -d "Don't start the instance after creation"

            # ── build ────────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand build' -a '(__isx_templates)' -d 'Template name'
            complete -c isx -f -n '__isx_using_subcommand build' -l all              -d 'Rebuild all defined templates'
            complete -c isx -f -n '__isx_using_subcommand build' -l out-of-sync     -d 'Rebuild templates that are out of sync'
            complete -c isx -f -n '__isx_using_subcommand build' -l with-parents    -d 'Rebuild the template and all its parents'
            complete -c isx -f -n '__isx_using_subcommand build' -l with-descendants -d 'Rebuild the template and all templates inheriting from it'
            complete -c isx -f -n '__isx_using_subcommand build' -l missing          -d 'Build only templates that don'"'"'t exist yet'
            complete -c isx -f -n '__isx_using_subcommand build' -l type             -d 'Instance type: container, vm, or kvm' -a 'container vm kvm'
            complete -c isx -f -n '__isx_using_subcommand build' -l yes              -d 'Skip interactive confirmations'

            # ── clean ────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand clean; and not string match -qr -- "\\b(cache|state|config|pool|all)\\b" (commandline -opc)' -a cache  -d 'Remove cached downloads, registry blobs, and build caches'
            complete -c isx -f -n '__isx_using_subcommand clean; and not string match -qr -- "\\b(cache|state|config|pool|all)\\b" (commandline -opc)' -a state  -d 'Remove VM state, logs, and appliance artifacts'
            complete -c isx -f -n '__isx_using_subcommand clean; and not string match -qr -- "\\b(cache|state|config|pool|all)\\b" (commandline -opc)' -a config -d 'Remove configuration, SSH keys, and CA certificate'
            complete -c isx -f -n '__isx_using_subcommand clean; and not string match -qr -- "\\b(cache|state|config|pool|all)\\b" (commandline -opc)' -a pool   -d 'Reclaim space from the storage pool'
            complete -c isx -f -n '__isx_using_subcommand clean; and not string match -qr -- "\\b(cache|state|config|pool|all)\\b" (commandline -opc)' -a all    -d 'Remove all incus-spawn data'

            complete -c isx -f -n '__isx_using_subcommand clean; and string match -qr -- "\\b(cache|state|config|pool|all)\\b" (commandline -opc)' -l dry-run           -d 'Show what would be deleted without deleting'
            complete -c isx -f -n '__isx_using_subcommand clean; and string match -qr -- "\\b(cache|state|config|pool|all)\\b" (commandline -opc)' -l skip-confirmation -d 'Skip the confirmation prompt'

            # ── destroy ──────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand destroy' -a '(__isx_instances)' -d 'Environment name'
            complete -c isx -f -n '__isx_using_subcommand destroy' -l all-templates      -d 'Destroy all built templates (reverse order, derived first)'
            complete -c isx -f -n '__isx_using_subcommand destroy' -l all-instances       -d 'Destroy all instances'
            complete -c isx -f -n '__isx_using_subcommand destroy' -l skip-confirmation   -d 'Skip the confirmation prompt'

            # ── reset ───────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand reset' -l skip-confirmation -d 'Skip the confirmation prompt'

            # ── list ─────────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand list' -l plain -d 'Deprecated: plain output is the default for list (no-op)'

            # ── shell ────────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand shell' -a '(__isx_instances)' -d 'Clone name'

            # ── run ─────────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand run' -a '(__isx_instances)' -d 'Instance name'
            complete -c isx -f -n '__isx_using_subcommand run' -l action -d 'Action to run (tool-name or tool-name:action-id)'

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

            # ── tools ────────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand tools; and not string match -qr -- "\\b(list|show)\\b" (commandline -opc)' -a list -d 'List available tools'
            complete -c isx -f -n '__isx_using_subcommand tools; and not string match -qr -- "\\b(list|show)\\b" (commandline -opc)' -a show -d 'Show details of a tool definition'

            complete -c isx -f -n '__isx_using_subcommand tools; and __isx_using_subcommand list' -s v -l verbose -d 'Show source and description'
            complete -c isx -f -n '__isx_using_subcommand tools; and __isx_using_subcommand show' -a '(__isx_tools)' -d 'Tool name'

            # ── proxy ────────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand proxy; and not string match -qr -- "\\b(start|stop|restart|status|install|uninstall|logs|dump)\\b" (commandline -opc)' -a start     -d 'Start the MITM authentication proxy'
            complete -c isx -f -n '__isx_using_subcommand proxy; and not string match -qr -- "\\b(start|stop|restart|status|install|uninstall|logs|dump)\\b" (commandline -opc)' -a stop      -d 'Stop the proxy'
            complete -c isx -f -n '__isx_using_subcommand proxy; and not string match -qr -- "\\b(start|stop|restart|status|install|uninstall|logs|dump)\\b" (commandline -opc)' -a restart   -d 'Restart the proxy service'
            complete -c isx -f -n '__isx_using_subcommand proxy; and not string match -qr -- "\\b(start|stop|restart|status|install|uninstall|logs|dump)\\b" (commandline -opc)' -a status    -d 'Check if the proxy is running'
            complete -c isx -f -n '__isx_using_subcommand proxy; and not string match -qr -- "\\b(start|stop|restart|status|install|uninstall|logs|dump)\\b" (commandline -opc)' -a install   -d 'Install the proxy as a systemd user service'
            complete -c isx -f -n '__isx_using_subcommand proxy; and not string match -qr -- "\\b(start|stop|restart|status|install|uninstall|logs|dump)\\b" (commandline -opc)' -a uninstall -d 'Stop and remove the systemd proxy service'
            complete -c isx -f -n '__isx_using_subcommand proxy; and not string match -qr -- "\\b(start|stop|restart|status|install|uninstall|logs|dump)\\b" (commandline -opc)' -a logs      -d 'Follow the proxy log file in real time'
            complete -c isx -f -n '__isx_using_subcommand proxy; and not string match -qr -- "\\b(start|stop|restart|status|install|uninstall|logs|dump)\\b" (commandline -opc)' -a dump      -d 'Run a local pass-through proxy for API traffic capture'

            complete -c isx -f -n '__isx_using_subcommand proxy; and __isx_using_subcommand start' -l port        -d 'MITM TLS proxy port'
            complete -c isx -f -n '__isx_using_subcommand proxy; and __isx_using_subcommand start' -l health-port -d 'Health check HTTP port'
            complete -c isx -f -n '__isx_using_subcommand proxy; and __isx_using_subcommand start' -l gateway-ip  -d 'Incus bridge gateway IP (skips Incus API lookup)'
            complete -c isx -f -n '__isx_using_subcommand proxy; and __isx_using_subcommand start' -l debug       -d 'Log full API request/response details'
            complete -c isx -f -n '__isx_using_subcommand proxy; and __isx_using_subcommand dump'  -l port        -d 'Local HTTP port'

            # ── vm ──────────────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand vm; and not string match -qr -- "\\b(start|stop|restart|status|resize|console)\\b" (commandline -opc)' -a start   -d 'Start the VM (creates disk image on first run)'
            complete -c isx -f -n '__isx_using_subcommand vm; and not string match -qr -- "\\b(start|stop|restart|status|resize|console)\\b" (commandline -opc)' -a stop    -d 'Stop the VM (graceful shutdown)'
            complete -c isx -f -n '__isx_using_subcommand vm; and not string match -qr -- "\\b(start|stop|restart|status|resize|console)\\b" (commandline -opc)' -a restart -d 'Stop and restart the VM (applies pending appliance updates)'
            complete -c isx -f -n '__isx_using_subcommand vm; and not string match -qr -- "\\b(start|stop|restart|status|resize|console)\\b" (commandline -opc)' -a status  -d 'Show VM status and system diagnostics'
            complete -c isx -f -n '__isx_using_subcommand vm; and not string match -qr -- "\\b(start|stop|restart|status|resize|console)\\b" (commandline -opc)' -a resize  -d 'Grow the VM data disk that backs the storage pool'
            complete -c isx -f -n '__isx_using_subcommand vm; and not string match -qr -- "\\b(start|stop|restart|status|resize|console)\\b" (commandline -opc)' -a console -d 'Follow VM serial console output'

            # ── update-base ─────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand update-base' -l list   -d 'List available versions'
            complete -c isx -f -n '__isx_using_subcommand update-base' -l latest -d 'Track the latest version (remove any pin)'

            # ── doctor ──────────────────────────────────────────────────────────────

            complete -c isx -f -n '__isx_using_subcommand doctor' -l bundle -d 'Collect findings and logs into a support archive'
            complete -c isx -f -n '__isx_using_subcommand doctor' -l deep   -d 'Run per-instance checks (DNS, TLS, resolv.conf)'
            """;
}
