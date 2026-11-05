package dev.incusspawn.incus;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory Incus daemon behind an {@link IncusClient}, recording every request the client
 * sends. Latency regressions in the CLI almost always arrive as extra round trips (a
 * {@code configGet} per key, a GET per listed instance), and a round-trip count is
 * deterministic where wall-clock timing on shared CI runners is not. Tests pin the count for a
 * flow; see {@code InstanceLifecycleRequestBudgetTest}.
 *
 * <p>Serves the subset of the API those flows use: server info, instance GET/PUT/PATCH/state,
 * instance listing, rename and DELETE, the {@code default} pool's volume listing, console log GET,
 * network, network lease and profile GET, network ACL GET/PUT/POST/DELETE, a running guest's address in its state, file push and async-operation waits. Anything else answers 404, so an unexpected request still shows up in
 * {@link #requests()}. Exec is among them: every instance behaves as one whose agent never
 * answers. A running VM refuses a disk device beyond its PCI hotplug slots, as Incus does
 * ({@link #hotplugSlots}), and one whose name it could not mount (#1513), which Incus does not.
 *
 * <p>Every request, setter and read of its state holds one lock, so a stubbed exec may inspect or
 * change the daemon while other threads' requests write it (#1478).
 */
public final class FakeIncusDaemon implements IncusTransport {

    private final Map<String, JqObject> instances = new LinkedHashMap<>();
    /**
     * Profiles by name. {@code expanded_devices} is always an instance's profiles' devices with
     * its own on top, as Incus computes it: removing an instance device that shadows a profile
     * device brings the profile's back.
     */
    private final Map<String, JqObject> profiles = new LinkedHashMap<>();
    private final Map<String, JqObject> networks = new LinkedHashMap<>();
    private final Map<String, JqObject> networkAcls = new LinkedHashMap<>();
    /** The firewall driver {@code GET /1.0} reports; Incus accepts NIC ACLs only from nftables. */
    private String firewall = "nftables";
    private final List<JqObject> extraVolumes = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();
    private final List<Exec> execs = new ArrayList<>();
    /** An exec asked for: its command, and the environment it asked for on top of the defaults. */
    public record Exec(List<String> command, Map<String, String> environment) {}
    private final List<String> refusedWrites = new ArrayList<>();
    /** Bridged-NIC ACLs by default, as every Incus isx is tested against has them (#1979). */
    private final List<String> apiExtensions = new ArrayList<>(List.of(NIC_ACL_EXTENSION));
    private static final String NIC_ACL_EXTENSION = "network_bridge_acl_devices";
    private record Pushed(String mode, String content) {}
    private final Map<String, Pushed> pushed = new LinkedHashMap<>();
    private final Map<String, String> consoleLogs = new LinkedHashMap<>();
    /** The {@code processes} each state read reports, consumed in order; the last one sticks. */
    private final Map<String, ArrayDeque<Integer>> agentProcesses = new LinkedHashMap<>();
    /** Running instances' init pids; every start gets a fresh one, as a new QEMU process would. */
    private final Map<String, Long> pids = new LinkedHashMap<>();
    private final List<String> shutdownIgnored = new ArrayList<>();
    private final List<String> stateActions = new ArrayList<>();
    /** Dynamic DHCP leases on the bridge, by address, as another MAC holds them. */
    private final List<String> leases = new ArrayList<>();
    /** The address a running instance's DHCP client got, where it is not its NIC's reservation. */
    private final Map<String, String> dhcpAnswers = new LinkedHashMap<>();
    private final Map<String, String> guestInterfaces = new LinkedHashMap<>();
    /** What an instance listing answers in place of the instances, when set. */
    private JqValue listingOverride;
    /** Storage pools by name, with their driver; empty means one btrfs pool, {@code default}. */
    private final Map<String, String> pools = new LinkedHashMap<>();
    /** Each pool's config, as the listing and a pool PATCH see it. */
    private final Map<String, Map<String, String>> poolConfigs = new LinkedHashMap<>();
    private boolean poolListingFails;
    private final java.util.Set<String> customVolumes = new java.util.LinkedHashSet<>();
    private RawResponse customVolumeFailure;
    private String subvolumeListing;
    private final java.util.Set<String> images = new java.util.LinkedHashSet<>();
    /** Local image aliases, and the image a remote alias was last fetched as. */
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final Map<String, String> fetched = new LinkedHashMap<>();
    /** Images whose /sbin/init cannot run: an instance created from one fails to start. */
    private final java.util.Set<String> brokenInit = new java.util.LinkedHashSet<>();
    private final Map<String, String> lxcLogs = new LinkedHashMap<>();
    private final List<String> brokenFetches = new ArrayList<>();
    private int nextFetch = 1;
    private boolean refuseNextWrite;
    /** Errors the next network PATCHes answer with, in order; each leaves the network unchanged. */
    private final ArrayDeque<String> networkWriteErrors = new ArrayDeque<>();
    private final Map<String, ArrayDeque<Runnable>> writesAfterRead = new LinkedHashMap<>();
    private int failReadsWith;
    /** What the next operation reads answer, in order; {@code Success} once it is empty. */
    private final ArrayDeque<String> operationStatuses = new ArrayDeque<>();
    private int nextOperation = 1;
    private long nextPid = 1000;
    /**
     * Disk devices added to each running VM, by name. Incus gives a running VM a fixed pool of
     * PCI hotplug slots and each directory disk added after start takes one until removed;
     * devices present at start are boot devices and take none (#826, #828).
     */
    private final Map<String, java.util.Set<String>> hotplugged = new LinkedHashMap<>();
    private int hotplugSlots = 8;
    /** Whether execs are served (each exits 0); otherwise none is, and a readiness wait times out. */
    private boolean servesExecs;
    /** What a served exec prints, by a string its command line contains; the first match wins. */
    private final Map<String, String> execOutputs = new LinkedHashMap<>();
    /** What each served exec's stdout socket delivers, by its secret. */
    private final Map<String, byte[]> execStdout = new LinkedHashMap<>();
    /** The size of every frame sent to a served exec's stdin, in order. */
    private final List<Integer> execStdinFrames = new ArrayList<>();
    /** The secret of each served exec's stdin fd. */
    private final java.util.Set<String> execStdinSecrets = new java.util.HashSet<>();
    private int nextExec = 1;

    public FakeIncusDaemon() {
        network("incusbr0", Map.of("ipv4.address", "10.166.11.1/24"));
        profiles.put("default", JqObject.ofEntries("name", "default", "devices", JqObject.ofEntries(
                "eth0", JqObject.ofEntries("type", "nic", "network", "incusbr0", "name", "eth0"),
                "root", JqObject.ofEntries("type", "disk", "path", "/", "pool", "default"))));
    }

    /** Define (or replace) a profile with exactly these devices. */
    public synchronized FakeIncusDaemon profile(String name, Map<String, Map<String, String>> devices) {
        profiles.put(name, JqObject.ofEntries("name", name, "devices", tree(devices)));
        return this;
    }

    /** A profile's devices as they are now, device name to config. */
    public synchronized Map<String, Map<String, String>> profileDevices(String name) {
        return deviceMaps(profiles.get(name).path("devices"));
    }

    /** An instance's own devices as they are now, device name to config. */
    public synchronized Map<String, Map<String, String>> instanceDevices(String name) {
        return deviceMaps(instances.get(name).path("devices"));
    }

    /** Add a device to an existing instance, as if it had been configured or copied over. */
    public synchronized FakeIncusDaemon device(String instanceName, String deviceName, Map<String, String> config) {
        var instance = instances.get(instanceName);
        instances.put(instanceName, instance.with("devices", object(instance.path("devices")).with(deviceName, tree(config))));
        expand(instanceName);
        return this;
    }

    /**
     * Give an instance the bridge NIC override {@code isx branch} writes: a pinned address with IP
     * spoofing protection on, the steady state of every instance branched since #905's check.
     * Without it the profile's NIC has no filtering, which the pre-start repair turns back on.
     */
    public synchronized FakeIncusDaemon ipFiltering(String instanceName, String value) {
        return device(instanceName, "eth0", Map.of("type", "nic", "network", "incusbr0", "name", "eth0",
                "ipv4.address", "10.166.11.20", "security.ipv4_filtering", value));
    }

    /** How many disk devices a running VM can have hot-plugged at once; Incus allocates 8. */
    public synchronized FakeIncusDaemon hotplugSlots(int slots) {
        hotplugSlots = slots;
        return this;
    }

    /** Add a stopped container with the given config. */
    public synchronized FakeIncusDaemon container(String name, Map<String, String> config) {
        return instance(name, "container", "Stopped", config);
    }

    public synchronized FakeIncusDaemon instance(String name, String type, String status, Map<String, String> config) {
        instances.put(name, JqObject.ofEntries("name", name, "type", type, "status", status,
                "architecture", "x86_64", "config", tree(config), "profiles", JqArray.of(JqString.of("default")),
                "devices", JqObject.EMPTY));
        if (holdsLiveState(status)) pids.put(name, nextPid++);
        expand(name);
        return this;
    }

    /** A cached image, by fingerprint. */
    public synchronized FakeIncusDaemon image(String fingerprint) {
        images.add(fingerprint);
        return this;
    }

    /** A local image under {@code alias}. */
    public synchronized FakeIncusDaemon imageAlias(String alias, String fingerprint) {
        images.add(fingerprint);
        aliases.put(alias, fingerprint);
        return this;
    }

    /**
     * The image's {@code /sbin/init} fails to exec: starting an instance made from it fails, as
     * Incus's forkstart does, and the instance's {@code lxc.log} says why, as LXC writes it.
     */
    public synchronized FakeIncusDaemon brokenInit(String fingerprint) {
        brokenInit.add(fingerprint);
        return this;
    }

    /** The next fetch of the remote {@code alias} comes back with a broken init; call it again for the one after. */
    public synchronized FakeIncusDaemon nextFetchBrokenInit(String alias) {
        brokenFetches.add(alias);
        return this;
    }

    /** The image a remote alias was fetched as, or null if it never was. */
    public synchronized String fetchedAs(String remoteAlias) {
        return fetched.get(remoteAlias);
    }

    public synchronized boolean hasImage(String fingerprint) {
        return images.contains(fingerprint);
    }

    /** A storage pool; once one is declared, only declared pools are listed. */
    public synchronized FakeIncusDaemon pool(String name, String driver) {
        pools.put(name, driver);
        return this;
    }

    /** A pool's config keys, such as a loop file's {@code source} and {@code size}. */
    public synchronized FakeIncusDaemon poolConfig(String name, Map<String, String> config) {
        poolConfigs.put(name, new LinkedHashMap<>(config));
        return this;
    }

    public synchronized Map<String, String> poolConfigOf(String name) {
        return Map.copyOf(poolConfigs.getOrDefault(name, Map.of()));
    }

    /** Answer the pool listing with a 500 from now on, as a daemon that cannot say would. */
    public synchronized FakeIncusDaemon failPoolListing() {
        poolListingFails = true;
        return this;
    }

    /** A custom volume, such as the shared DNF cache, on {@code pool}. */
    public synchronized FakeIncusDaemon customVolume(String pool, String name) {
        customVolumes.add(pool + "/" + name);
        return this;
    }

    public synchronized boolean hasCustomVolume(String pool, String name) {
        return customVolumes.contains(pool + "/" + name);
    }

    /** Answer every read or delete of a custom volume with this error, as a busy or broken server does. */
    public synchronized FakeIncusDaemon customVolumesFail(int status, String error) {
        customVolumeFailure = error(status, error);
        return this;
    }

    /** Answer instance listings with this metadata instead, as a broken or foreign server might. */
    public synchronized FakeIncusDaemon listingAnswers(JqValue metadata) {
        listingOverride = metadata;
        return this;
    }

    /** Have the guest ignore a graceful stop, as a wedged one does: only a forced stop works. */
    public synchronized FakeIncusDaemon ignoreShutdown(String instanceName) {
        shutdownIgnored.add(instanceName);
        return this;
    }

    /**
     * A dynamic lease on the bridge held by some other MAC, as a stopped or deleted instance
     * leaves one behind until it expires.
     */
    public synchronized FakeIncusDaemon lease(String address) {
        leases.add(address);
        return this;
    }

    /**
     * Have the instance's DHCP client get {@code address} instead of its NIC's reservation, as
     * dnsmasq answers when another MAC still holds a lease on the reserved one.
     */
    public synchronized FakeIncusDaemon dhcpAnswers(String instanceName, String address) {
        dhcpAnswers.put(instanceName, address);
        return this;
    }

    /** Another interface of the running guest holding {@code address}, as a docker0 bridge does. */
    public synchronized FakeIncusDaemon guestInterface(String instanceName, String address) {
        guestInterfaces.put(instanceName, address);
        return this;
    }

    /** Each {@code PUT /state} so far, as {@code "<instance> <action>"} plus {@code " force"} if forced. */
    public synchronized List<String> stateActions() {
        return List.copyOf(stateActions);
    }

    public synchronized void clearStateActions() {
        stateActions.clear();
    }

    private void expand(String instanceName) {
        var instance = instances.get(instanceName);
        var expanded = JqObject.EMPTY;
        for (var p : instance.path("profiles").asList()) expanded = expanded.merge(object(profiles.get(p.asText()).path("devices")));
        instances.put(instanceName, instance.with("expanded_devices", expanded.merge(object(instance.path("devices")))));
    }

    /**
     * Refuse any instance PUT or PATCH whose body contains {@code needle}, as Incus refuses a
     * setting it cannot apply: a 400 and no change.
     */
    public synchronized FakeIncusDaemon refuseWritesContaining(String needle) {
        refusedWrites.add(needle);
        return this;
    }

    /**
     * A volume on the {@code default} pool with no instance of this daemon behind it, as another
     * Incus project's instance has.
     */
    public synchronized FakeIncusDaemon volume(String project, String name, String type) {
        extraVolumes.add(JqObject.ofEntries("name", name, "type", type, "project", project));
        return this;
    }

    /**
     * After each of the next reads of {@code instanceName}, run one of {@code writes}, in order:
     * another process changing the instance between a caller's read and its write.
     */
    public synchronized FakeIncusDaemon writeAfterRead(String instanceName, Runnable... writes) {
        writesAfterRead.computeIfAbsent(instanceName, n -> new ArrayDeque<>()).addAll(List.of(writes));
        return this;
    }

    /** Refuse the next instance PUT or PATCH, whatever it carries, as a transient failure would. */
    public synchronized FakeIncusDaemon refuseNextWrite() {
        refuseNextWrite = true;
        return this;
    }

    /** What Incus answers when the dnsmasq it restarts to apply raw.dnsmasq does not stay up. */
    public static final String DNSMASQ_EXITED = "The DNS and DHCP service exited prematurely: "
            + "exit status 2 (\"dnsmasq: failed to create listening socket for 10.1.2.1: Address already in use\")";

    /**
     * Refuse the next network PATCHes, one per error, with a 500 carrying it and no change: Incus
     * reverts a network update whose bridge setup fails, such as dnsmasq exiting on restart.
     */
    public synchronized FakeIncusDaemon refuseNetworkWrites(String... errors) {
        networkWriteErrors.addAll(List.of(errors));
        return this;
    }

    /** Answer every instance GET with this error status (0: answer normally), as a failing daemon would. */
    public synchronized FakeIncusDaemon failInstanceReads(int status) {
        failReadsWith = status;
        return this;
    }

    /** What the instance's console log holds, as {@code incus console --show-log} would print it. */
    public synchronized FakeIncusDaemon consoleLog(String instanceName, String log) {
        consoleLogs.put(instanceName, log);
        return this;
    }

    /**
     * Have the VM's state report {@code processes} as each read in turn, then the last value
     * from there on: -1 while its agent is not connected, as Incus reports it, then a real count.
     * Without this the state carries no {@code processes} at all.
     */
    public synchronized FakeIncusDaemon agentProcesses(String instanceName, int... reads) {
        var queue = new ArrayDeque<Integer>();
        for (int r : reads) queue.add(r);
        agentProcesses.put(instanceName, queue);
        return this;
    }

    /** The instance as a GET would return it. */
    public synchronized JqObject instance(String name) {
        return instances.get(name);
    }

    /** Set when {@code instanceName} last started, which Incus records on every start. */
    public synchronized FakeIncusDaemon lastUsedAt(String instanceName, String at) {
        set(instanceName, "last_used_at", at);
        return this;
    }

    public synchronized FakeIncusDaemon network(String name, Map<String, String> config) {
        networks.put(name, JqObject.ofEntries("name", name, "config", tree(config)));
        return this;
    }

    /** Drop a network, the default {@code incusbr0} included, so reads of it fail. */
    public synchronized FakeIncusDaemon withoutNetwork(String name) {
        networks.remove(name);
        return this;
    }

    /**
     * Serve execs: each exits 0 having printed nothing, or what {@link #execOutput} gives its
     * command. Their round trips are recorded like any other: the POST, a WebSocket per fd
     * ({@code WS ...}) and the operation wait.
     */
    public synchronized FakeIncusDaemon servesExecs() {
        servesExecs = true;
        return this;
    }

    /** A served exec whose command line contains {@code needle} prints {@code stdout}. */
    public synchronized FakeIncusDaemon execOutput(String needle, String stdout) {
        servesExecs = true;
        execOutputs.put(needle, stdout);
        return this;
    }

    /** A client wired to this daemon. */
    /**
     * A client whose readiness wait gives up after a few milliseconds, for tests outside this
     * package that go through a start: this daemon serves no exec, so the wait can only time out.
     */
    public synchronized IncusClient clientWithShortReadyWait() {
        var client = client();
        client.readyTimeouts(new IncusClient.ReadyTimeouts(java.time.Duration.ofMillis(20),
                java.time.Duration.ofMillis(20), java.time.Duration.ofMillis(10), java.time.Duration.ofMillis(5),
                java.time.Duration.ofMillis(5)));
        return client;
    }

    public synchronized IncusClient client() {
        var client = new IncusClient(new IncusApi(this));
        if (subvolumeListing != null) client.subvolumeLister = pool -> subvolumeListing;
        return client;
    }

    /**
     * What {@code btrfs subvolume list} prints for a pool, for clients made from here on; without
     * it a client runs the real command, which a test host cannot answer.
     */
    public synchronized FakeIncusDaemon subvolumeListing(String listing) {
        subvolumeListing = listing;
        return this;
    }

    /** {@link #subvolumeListing} with a container subvolume for each name. */
    public synchronized FakeIncusDaemon containerSubvolumes(String... names) {
        return subvolumeListing(listing(names));
    }

    /** {@code btrfs subvolume list} output with a container subvolume for each name. */
    public static String listing(String... names) {
        var sb = new StringBuilder();
        int id = 300;
        for (var n : names) sb.append("ID ").append(id++).append(" gen 1 top level 5 path containers/").append(n).append('\n');
        return sb.toString();
    }

    /** The mode a file was last pushed into an instance with, or null if it never was. */
    public synchronized String pushedMode(String instanceName, String path) {
        var p = pushed.get(instanceName + path);
        return p == null ? null : p.mode();
    }

    /** What a file was last pushed into an instance with, or null if it never was. */
    public synchronized String pushedContent(String instanceName, String path) {
        var p = pushed.get(instanceName + path);
        return p == null ? null : p.content();
    }

    /** Every request so far, as {@code "METHOD path"}, in order. */
    public synchronized List<String> requests() {
        return List.copyOf(requests);
    }

    /** Every exec asked for, in order, whether or not it was served (none is). */
    public synchronized List<Exec> execs() {
        return List.copyOf(execs);
    }

    public synchronized void clearRequests() {
        requests.clear();
    }

    @Override
    public synchronized RawResponse request(String method, String path, String contentType,
                               Map<String, String> extraHeaders, byte[] body) throws IOException {
        requests.add(method + " " + path);
        if (method.equals("POST") && path.endsWith("/exec") && body != null) {
            var exec = parse(body);
            var command = new ArrayList<String>();
            exec.path("command").asList().forEach(arg -> command.add(arg.asText()));
            execs.add(new Exec(command, strings(exec.path("environment"))));
            if (servesExecs) return startExec(String.join(" ", command));
        }
        return handle(method, path, extraHeaders, body);
    }

    @Override
    public synchronized RawResponse request(String method, String path, String contentType,
                               Map<String, String> extraHeaders, Path bodyFile) {
        requests.add(method + " " + path);
        var name = instanceName(path);
        if (name == null || !instances.containsKey(name) || !path.contains("/files?")) return notFound();
        var file = java.net.URLDecoder.decode(path.substring(path.indexOf("path=") + 5),
                java.nio.charset.StandardCharsets.UTF_8);
        try {
            pushed.put(name + file, new Pushed(extraHeaders.getOrDefault("X-Incus-mode", ""),
                    java.nio.file.Files.readString(bodyFile)));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return sync(JqObject.EMPTY);
    }

    @Override
    public synchronized WsConnection openWebSocket(String wsPath) throws IOException {
        requests.add("WS " + wsPath);
        var marker = "/websocket?secret=";
        var secret = wsPath.contains(marker) ? wsPath.substring(wsPath.indexOf(marker) + marker.length()) : null;
        if (!servesExecs || secret == null) throw new IOException("FakeIncusDaemon does not serve WebSockets");
        return new ExecSocket(execStdout.getOrDefault(secret, new byte[0]), execStdinSecrets.contains(secret));
    }

    private synchronized void stdinFrame(int length) {
        execStdinFrames.add(length);
    }

    /** The size of every frame sent to a served exec's stdin, in order. */
    public synchronized List<Integer> execStdinFrames() {
        return List.copyOf(execStdinFrames);
    }

    /** An exec, started: an operation whose fds' sockets carry {@code stdout} and then close. */
    private RawResponse startExec(String commandLine) {
        var id = "exec-" + nextExec++;
        var stdout = execOutputs.entrySet().stream().filter(e -> commandLine.contains(e.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse("");
        var fds = JqObject.ofEntries("0", id + "-0", "1", id + "-1", "2", id + "-2", "control", id + "-control");
        execStdout.put(id + "-1", stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        execStdinSecrets.add(id + "-0");
        return async(id, JqObject.ofEntries("id", id, "metadata", JqObject.of("fds", fds)));
    }

    /** One fd of a served exec: its bytes, if any, then end of stream. */
    private final class ExecSocket implements WsConnection {
        private byte[] pending;
        private final boolean stdin; // fd "0": a control message on another fd is no stdin frame

        ExecSocket(byte[] data, boolean stdin) {
            pending = data.length > 0 ? data : null;
            this.stdin = stdin;
        }

        @Override
        public synchronized byte[] readPayload() {
            var data = pending;
            pending = null;
            return data;
        }

        @Override public byte[] readMessage() { return readPayload(); }
        @Override public long millisSinceLastReceived() { return 0; }
        @Override public synchronized boolean hasUnread() { return pending != null; }
        @Override public void sendData(byte[] data, int offset, int length) { if (stdin) stdinFrame(length); }
        @Override public void sendPing() {}
        @Override public void sendClose() {}
        @Override public void close() {}
    }

    /**
     * Answer the next operation reads with these statuses, in order, then {@code Success}. A
     * {@code Running} is what Incus answers a {@code /wait} whose long-poll timed out: HTTP 200,
     * not finished.
     */
    public synchronized FakeIncusDaemon operationsAnswer(String... statuses) {
        operationStatuses.addAll(List.of(statuses));
        return this;
    }

    /** Report this firewall driver in {@code GET /1.0}, and refuse NIC ACLs unless it is {@code nftables}. */
    public synchronized FakeIncusDaemon firewall(String driver) {
        firewall = driver;
        return this;
    }

    /** A network ACL as it is now, or null. */
    public synchronized JqObject networkAcl(String name) {
        return networkAcls.get(name);
    }

    /**
     * Advertise these in {@code GET /1.0}'s {@code api_extensions}, and only these: without
     * {@code network_bridge_acl_devices} a NIC's ACL keys are refused as unknown options, as
     * Incus 6.0.0 refuses them.
     */
    public synchronized FakeIncusDaemon apiExtensions(String... extensions) {
        apiExtensions.clear();
        apiExtensions.addAll(List.of(extensions));
        return this;
    }

    private RawResponse handle(String method, String path, Map<String, String> headers,
                               byte[] body) throws IOException {
        if (failReadsWith != 0 && method.equals("GET") && path.startsWith("/1.0/instances/")) {
            return error(failReadsWith, "refused by FakeIncusDaemon");
        }
        if (path.equals("/1.0") && method.equals("GET")) {
            var extensions = JqArray.of(apiExtensions.stream().<JqValue>map(JqString::of).toList());
            return sync(JqObject.ofEntries("api_extensions", extensions, "environment", JqObject.of("firewall", firewall)));
        }
        if (path.equals("/1.0/network-acls") && method.equals("POST")) {
            var acl = object(parse(body));
            var aclName = acl.path("name").asText("");
            if (networkAcls.containsKey(aclName)) return error(409, "The network ACL already exists");
            networkAcls.put(aclName, acl);
            return sync(JqObject.EMPTY);
        }
        if (path.startsWith("/1.0/network-acls/")) {
            var aclName = path.substring("/1.0/network-acls/".length());
            var acl = networkAcls.get(aclName);
            if (acl == null) return notFound();
            if (method.equals("GET")) return sync(acl);
            if (method.equals("DELETE")) {
                // Incus refuses to delete an ACL a NIC uses
                for (var i : instances.values()) {
                    for (var device : i.path("expanded_devices").asMap().values()) {
                        if (device.path("security.acls").asText("").contains(aclName)) return badRequest("Cannot delete an ACL that is in use");
                    }
                }
                networkAcls.remove(aclName);
                return sync(JqObject.EMPTY);
            }
            if (method.equals("PUT")) {
                networkAcls.put(aclName, object(parse(body)).with("name", JqString.of(aclName)));
                return sync(JqObject.EMPTY);
            }
        }
        if (path.startsWith("/1.0/operations/") && method.equals("GET")) {
            var status = operationStatuses.poll();
            return sync(JqObject.of("status", status != null ? status : "Success"));
        }
        if (path.startsWith("/1.0/instances?") && method.equals("GET")) {
            if (listingOverride != null) return sync(listingOverride);
            return sync(JqArray.of(List.<JqValue>copyOf(instances.values())));
        }
        if (path.startsWith("/1.0/storage-pools/") && path.contains("/volumes/custom/")
                && (method.equals("GET") || method.equals("DELETE"))) {
            if (customVolumeFailure != null) return customVolumeFailure;
            var key = path.substring("/1.0/storage-pools/".length()).replace("/volumes/custom/", "/");
            if (method.equals("GET")) {
                return customVolumes.contains(key) ? sync(JqObject.of("name", key)) : notFound();
            }
            return customVolumes.remove(key) ? sync(JqObject.EMPTY) : notFound();
        }
        if (path.startsWith("/1.0/storage-pools/") && path.endsWith("/volumes") && method.equals("POST")) {
            var pool = path.substring("/1.0/storage-pools/".length(), path.length() - "/volumes".length());
            customVolumes.add(pool + "/" + parse(body).path("name").asText(""));
            return sync(JqObject.EMPTY);
        }
        if (path.startsWith("/1.0/storage-pools?") && method.equals("GET")) {
            if (poolListingFails) return error(500, "pool listing failed");
            var list = new ArrayList<JqValue>();
            (pools.isEmpty() ? Map.of("default", "btrfs") : pools).forEach((name, driver) -> list.add(JqObject.ofEntries(
                    "name", name, "driver", driver, "config", tree(poolConfigs.getOrDefault(name, Map.of())))));
            return sync(JqArray.of(list));
        }
        if (path.startsWith("/1.0/storage-pools/") && !path.substring("/1.0/storage-pools/".length()).contains("/")
                && method.equals("PATCH")) {
            // Incus merges a PATCH's config keys into the pool's
            var config = poolConfigs.computeIfAbsent(path.substring("/1.0/storage-pools/".length()), k -> new LinkedHashMap<>());
            config.putAll(strings(parse(body).path("config")));
            return sync(JqObject.EMPTY);
        }
        if (path.equals("/1.0/instances") && method.equals("POST")) {
            var request = parse(body);
            return "image".equals(request.path("source").path("type").asText(""))
                    ? createFromImage(request) : copy(request);
        }
        if (path.startsWith("/1.0/images/") && !path.startsWith("/1.0/images/aliases") && method.equals("DELETE")) {
            var fingerprint = path.substring("/1.0/images/".length());
            aliases.values().removeIf(fingerprint::equals);
            return images.remove(fingerprint) ? async() : notFound();
        }
        if (path.startsWith("/1.0/profiles/") && method.equals("GET")) {
            var profile = profiles.get(path.substring("/1.0/profiles/".length()));
            return profile == null ? notFound() : sync(profile);
        }
        if (path.startsWith("/1.0/profiles/") && method.equals("PATCH")) {
            var profileName = path.substring("/1.0/profiles/".length());
            var profile = profiles.get(profileName);
            if (profile == null) return notFound();
            profiles.put(profileName, patchDevices(profile, parse(body)));
            List.copyOf(instances.keySet()).forEach(this::expand);
            return sync(JqObject.EMPTY);
        }
        if (path.startsWith("/1.0/storage-pools/default/volumes?") && method.equals("GET")) {
            // Incus keeps a volume per instance, named after it, on the instance's pool.
            var list = new ArrayList<JqValue>();
            instances.values().forEach(i -> list.add(JqObject.ofEntries(
                    "name", i.path("name").asText(""), "type", i.path("type").asText(""), "project", "default")));
            list.addAll(extraVolumes);
            return sync(JqArray.of(list));
        }
        if (path.startsWith("/1.0/networks/") && path.endsWith("/leases") && method.equals("GET")) {
            var list = new ArrayList<JqValue>();
            leases.forEach(address -> list.add(JqObject.ofEntries("address", address, "type", "dynamic")));
            instances.values().forEach(i -> {
                var reserved = bridgeNicAddress(i);
                if (!reserved.isEmpty()) list.add(JqObject.ofEntries("address", reserved, "type", "static"));
            });
            return sync(JqArray.of(list));
        }
        if (path.startsWith("/1.0/networks/") && method.equals("GET")) {
            var network = networks.get(path.substring("/1.0/networks/".length()));
            return network == null ? notFound() : sync(network);
        }
        if (path.startsWith("/1.0/networks/") && method.equals("PATCH")) {
            // Incus merges a PATCH's config keys into the network's
            var networkName = path.substring("/1.0/networks/".length());
            var network = networks.get(networkName);
            if (network == null) return notFound();
            var refusal = networkWriteErrors.poll();
            if (refusal != null) return error(500, refusal);
            var config = object(network.path("config")).merge(object(parse(body).path("config")));
            networks.put(networkName, network.with("config", config));
            return sync(JqObject.EMPTY);
        }
        var name = instanceName(path);
        var instance = name == null ? null : instances.get(name);
        if (instance == null) return notFound();

        var rest = path.substring(("/1.0/instances/" + name).length());
        if (rest.isEmpty() && method.equals("GET")) {
            var read = sync(instance, etag(instance));
            var writes = writesAfterRead.get(name);
            var write = writes == null ? null : writes.poll();
            if (write != null) write.run();
            return read;
        }
        var post = rest.isEmpty() && method.equals("POST") ? parse(body) : null;
        if (post != null && post.has("pool")) {
            // A pool move: the name stays, and the root disk becomes a local device on the new pool.
            var pool = post.path("pool").asText("");
            if (!pools.isEmpty() && !pools.containsKey(pool)) return notFound();
            var root = object(instance.path("expanded_devices").path("root")).with("pool", JqString.of(pool));
            instances.put(name, instance.with("devices", object(instance.path("devices")).with("root", root)));
            expand(name);
            return async();
        }
        if (post != null) {
            var newName = post.path("name").asText("");
            instances.remove(name);
            instances.put(newName, instance.with("name", JqString.of(newName)));
            return async();
        }
        if (rest.isEmpty() && method.equals("DELETE")) {
            instances.remove(name);
            return async();
        }
        if (rest.isEmpty() && (method.equals("PUT") || method.equals("PATCH")) && body != null) {
            // Incus checks the ETag before it looks at what the write carries.
            var ifMatch = headers.get("If-Match");
            if (ifMatch != null && !ifMatch.equals(etag(instance))) return error(412, "ETag doesn't match");
            if (refuseNextWrite) {
                refuseNextWrite = false;
                return badRequest();
            }
            var text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            if (refusedWrites.stream().anyMatch(text::contains)) return badRequest();
        }
        if (rest.isEmpty() && method.equals("PUT")) {
            // A full replacement: how Incus removes devices (PATCH cannot).
            var replacement = parse(body);
            var stale = staleRunningNic(instance, replacement);
            if (stale != null) return badRequest(stale);
            var aclRefusal = aclRefusal(replacement);
            if (aclRefusal != null) return badRequest(aclRefusal);
            var refused = hotplug(name, instance, replacement);
            if (refused != null) return refused;
            var devices = object(replacement.path("devices"));
            instances.put(name, instance.with("config", object(replacement.path("config"))).with("devices", devices));
            var plugged = hotplugged.get(name);
            if (plugged != null) plugged.retainAll(devices.keys());
            expand(name);
            return sync(JqObject.EMPTY);
        }
        if (rest.isEmpty() && method.equals("PATCH")) {
            var patch = parse(body);
            var stale = staleRunningNic(instance, patch);
            if (stale != null) return badRequest(stale);
            var aclRefusal = aclRefusal(patch);
            if (aclRefusal == null) aclRefusal = keptAclRefusal(instance, patch, "Invalid devices: Device validation failed");
            if (aclRefusal != null) return badRequest(aclRefusal);
            var refused = hotplug(name, instance, patch);
            if (refused != null) return refused;
            instances.put(name, applyPatch(instance, patch));
            expand(name);
            return sync(JqObject.EMPTY);
        }
        if (rest.equals("/logs") && method.equals("GET")) {
            return sync(lxcLogs.containsKey(name)
                    ? JqArray.of(JqString.of("/1.0/instances/" + name + "/logs/lxc.log")) : JqArray.EMPTY);
        }
        if (rest.equals("/logs/lxc.log") && method.equals("GET")) {
            var log = lxcLogs.get(name);
            return log == null ? notFound() : new RawResponse(200, log.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (rest.equals("/console") && method.equals("GET")) {
            var log = consoleLogs.getOrDefault(name, "");
            return new RawResponse(200, log.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (rest.equals("/state") && method.equals("GET")) {
            var state = JqObject.ofEntries("status", instance.path("status").asText(""), "pid", pids.getOrDefault(name, 0L));
            var processes = agentProcesses.get(name);
            if (processes != null) {
                var read = processes.size() > 1 ? processes.poll() : processes.peek();
                state = state.with("processes", read == null ? JqNull.NULL : JqNumber.of(read));
            }
            // A running guest holds what DHCP gave it: its reservation, unless told otherwise
            var held = dhcpAnswers.getOrDefault(name, bridgeNicAddress(instance));
            if (holdsLiveState(instance.path("status").asText(""))) {
                var network = JqObject.EMPTY;
                if (guestInterfaces.containsKey(name)) network = network.with("docker0", inet(guestInterfaces.get(name)));
                if (!held.isEmpty()) network = network.with("eth0", inet(held));
                state = state.with("network", network);
            }
            return sync(state);
        }
        if (rest.equals("/state") && method.equals("PUT")) {
            var request = parse(body);
            var action = request.path("action").asText("");
            boolean force = request.path("force").asBoolean(false);
            stateActions.add(name + " " + action + (force ? " force" : ""));
            if (action.equals("stop") && !force && shutdownIgnored.contains(name)) return badRequest();
            // As Incus refuses it: a second start is how two isx processes collide on one box (#1065)
            if (action.equals("start") && "Running".equals(instance.path("status").asText(""))) {
                return badRequest("The instance is already running");
            }
            if (action.equals("start") || action.equals("restart")) {
                var aclRefusal = keptAclRefusal(instance, JqObject.EMPTY, "Failed start validation");
                if (aclRefusal != null) return badRequest(aclRefusal);
            }
            if (action.equals("start") && brokenInit.contains(instance.path("config").path("volatile.base_image").asText(""))) {
                set(name, "status", "Error");
                lxcLogs.put(name, "lxc " + name + " 20261006133238.740 ERROR    start - ../src/lxc/start.c:start:2208"
                        + " - Exec format error - Failed to exec \"/sbin/init\"\n");
                return badRequest();
            }
            hotplugged.remove(name);
            if (action.equals("start") || action.equals("restart")) {
                set(name, "status", "Running");
                pids.put(name, nextPid++);
                // What Incus sets on every start, a reboot included
                set(name, "last_used_at", java.time.Instant.now().plusNanos(nextPid).toString());
            } else {
                set(name, "status", "Stopped");
                pids.remove(name);
            }
            return async();
        }
        return notFound();
    }

    /**
     * Create an instance as a copy, as Incus does: the source's config and own devices, where
     * a device in the request replaces the source's whole and an empty config value unsets.
     */
    private RawResponse copy(JqValue request) {
        var source = request.path("source");
        var original = instances.get(source.path("source").asText(""));
        if (!"copy".equals(source.path("type").asText("")) || original == null) return notFound();
        var name = request.path("name").asText("");
        var config = object(original.path("config"));
        for (var e : request.path("config").asMap().entrySet()) {
            config = e.getValue().isString() && e.getValue().asText().isEmpty()
                    ? config.without(e.getKey()) : config.with(e.getKey(), e.getValue());
        }
        var devices = object(original.path("devices")).merge(object(request.path("devices")));
        instances.put(name, original.with("name", JqString.of(name)).with("status", JqString.of("Stopped"))
                .with("config", config).with("devices", devices));
        expand(name);
        return async();
    }

    /**
     * Create a stopped container from an image, recording it in {@code volatile.base_image} as
     * Incus does. A remote alias is fetched once and then served from the cache, until that
     * cached image is deleted.
     */
    private RawResponse createFromImage(JqValue request) {
        var source = request.path("source");
        var alias = source.path("alias").asText("");
        String fingerprint;
        if (source.has("server")) {
            fingerprint = fetched.get(alias);
            if (fingerprint == null || !images.contains(fingerprint)) {
                fingerprint = "fetched-" + nextFetch++;
                images.add(fingerprint);
                fetched.put(alias, fingerprint);
                if (brokenFetches.remove(alias)) brokenInit.add(fingerprint);
            }
        } else if (source.has("fingerprint")) {
            fingerprint = source.path("fingerprint").asText("");
            if (!images.contains(fingerprint)) return notFound();
        } else {
            fingerprint = aliases.get(alias);
            if (fingerprint == null || !images.contains(fingerprint)) return notFound();
        }
        var name = request.path("name").asText("");
        // Config given in the create request is set on the instance, as Incus does
        var config = strings(request.path("config"));
        config.put("volatile.base_image", fingerprint);
        instance(name, "container", "Stopped", config);
        return async();
    }

    private static JqObject applyPatch(JqObject instance, JqValue patch) {
        var config = object(instance.path("config"));
        for (var e : patch.path("config").asMap().entrySet()) {
            config = e.getValue().isNull() ? config.without(e.getKey()) : config.with(e.getKey(), e.getValue());
        }
        return patchDevices(instance.with("config", config), patch);
    }

    /**
     * Why Incus refuses this write, or null: for a running instance it validates each NIC device
     * the write touches with the address the NIC holds now, which a bridge subnet change has left
     * off the network's subnet (#1009). A stopped instance is not checked.
     */
    private String staleRunningNic(JqObject instance, JqValue patch) {
        if (!holdsLiveState(instance.path("status").asText(""))) return null;
        for (var e : patch.path("devices").asMap().entrySet()) {
            var current = instance.path("expanded_devices").path(e.getKey());
            // Incus validates what the write changes, so a PUT carrying the NIC as it is passes
            if (!"nic".equals(current.path("type").asText("")) || e.getValue().equals(current)) continue;
            var network = networks.get(current.path("network").asText(""));
            var address = current.path("ipv4.address").asText("");
            var bridge = network == null ? "" : network.path("config").path("ipv4.address").asText("");
            if (bridge.isEmpty() || address.isEmpty()) continue;
            if (!CidrUtils.isInSubnet(address, CidrUtils.parseCidr(bridge))) {
                return "Invalid devices: Device validation failed for \"" + e.getKey()
                        + "\": Device IP address \"" + address + "\" not within network \""
                        + network.path("name").asText("") + "\" subnet";
            }
        }
        return null;
    }

    /** What Incus says of any NIC ACL when its firewall driver is not nftables. */
    private static final String NO_ACLS = "Security ACLs are only supported when using nftables firewall";

    /**
     * Why Incus refuses the NIC ACLs this write sets, or null: it validates them against its API
     * extensions, its firewall driver and the ACLs that exist.
     */
    private String aclRefusal(JqValue patch) {
        for (var e : patch.path("devices").asMap().entrySet()) {
            var acls = e.getValue().path("security.acls").asText("");
            if (acls.isEmpty()) continue;
            var prefix = "Invalid devices: Device validation failed for \"" + e.getKey() + "\": ";
            if (!apiExtensions.contains(NIC_ACL_EXTENSION)) return prefix + "Invalid device option \"security.acls\"";
            if (!firewall.equals("nftables")) return prefix + NO_ACLS;
            for (var acl : acls.split(",")) {
                if (!networkAcls.containsKey(acl.strip())) return prefix + "Network ACL \"" + acl.strip() + "\" not found";
            }
        }
        return null;
    }

    /**
     * Why Incus refuses a write or a start because of a NIC ACL the instance already has and the
     * write leaves in place, or null: it validates every device each time, so once its firewall
     * driver is not nftables nothing about such an instance can change, nor can it start.
     */
    private String keptAclRefusal(JqObject instance, JqValue patch, String prefix) {
        if (firewall.equals("nftables")) return null;
        for (var e : instance.path("expanded_devices").asMap().entrySet()) {
            if (patch.path("devices").has(e.getKey()) || e.getValue().path("security.acls").asText("").isEmpty()) continue;
            return prefix + " for device \"" + e.getKey() + "\": " + NO_ACLS;
        }
        return null;
    }

    /** Each device in a PATCH replaces the instance's or profile's device of that name whole. */
    private static JqObject patchDevices(JqObject target, JqValue patch) {
        return target.with("devices", object(target.path("devices")).merge(object(patch.path("devices"))));
    }

    /**
     * Take a hotplug slot for each disk device a PATCH or PUT adds to a running VM, or refuse the
     * request, taking nothing: for the first device no slot is left for, as Incus does, or for a name
     * too long to mount. Incus accepts that one, but the guest never mounts it (#1513), so refusing it
     * here makes a long name fail a test instead of silently costing a VM its share.
     */
    private RawResponse hotplug(String name, JqObject instance, JqValue patch) {
        if (!"virtual-machine".equals(instance.path("type").asText(""))
                || !holdsLiveState(instance.path("status").asText(""))) return null;
        var plugged = hotplugged.computeIfAbsent(name, n -> new java.util.LinkedHashSet<>());
        var adding = new ArrayList<String>();
        for (var e : patch.path("devices").asMap().entrySet()) {
            if ("disk".equals(e.getValue().path("type").asText("")) && !instance.path("devices").has(e.getKey())) {
                if (e.getKey().length() > DeviceNames.VM_HOTPLUG_MAX_LENGTH) {
                    return badRequest("Disk device \"" + e.getKey() + "\" is longer than "
                            + DeviceNames.VM_HOTPLUG_MAX_LENGTH + " characters: a running VM would never mount it");
                }
                adding.add(e.getKey());
            }
        }
        int free = hotplugSlots - plugged.size();
        if (adding.size() > free) return noHotplugSlot(adding.get(Math.max(free, 0)));
        plugged.addAll(adding);
        return null;
    }

    /** What Incus answers when a running VM has no PCI hotplug slot left for a device. */
    private static RawResponse noHotplugSlot(String device) {
        return badRequest("Failed to start device \"" + device + "\": Failed to call monitor hook for block device:"
                + " No available PCI hotplug slots could be found");
    }

    /** A frozen guest is paused, not stopped: it keeps its process and its addresses. */
    private static boolean holdsLiveState(String status) {
        return status.equals("Running") || status.equals("Frozen");
    }

    private static JqObject inet(String address) {
        return JqObject.of("addresses", JqArray.of(JqObject.ofEntries("family", "inet", "scope", "global", "address", address)));
    }

    /** The {@code ipv4.address} of the instance's bridge NIC, or empty. */
    private static String bridgeNicAddress(JqValue instance) {
        for (var device : instance.path("expanded_devices").asMap().values()) {
            if ("nic".equals(device.path("type").asText("")) && "incusbr0".equals(device.path("network").asText(""))) {
                return device.path("ipv4.address").asText("");
            }
        }
        return "";
    }

    /** The instance a {@code /1.0/instances/<name>[/...][?...]} path addresses, or null. */
    private static String instanceName(String path) {
        var prefix = "/1.0/instances/";
        if (!path.startsWith(prefix)) return null;
        var rest = path.substring(prefix.length());
        var end = rest.length();
        for (var c : new char[] {'/', '?'}) {
            var i = rest.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        return rest.substring(0, end);
    }

    private static RawResponse sync(JqValue metadata) {
        return sync(metadata, null);
    }

    private static RawResponse sync(JqValue metadata, String etag) {
        var body = JqObject.ofEntries("type", "sync", "status_code", 200, "metadata", metadata);
        return new RawResponse(200, JqValues.serializeToBytes(body), etag);
    }

    /** The instance's writable state, hashed as Incus derives its ETag; quoted as Incus sends it. */
    private static String etag(JqObject instance) {
        var state = JqObject.ofEntries("architecture", instance.get("architecture"), "config", instance.get("config"),
                "devices", instance.get("devices"), "ephemeral", instance.get("ephemeral"), "profiles", instance.get("profiles"));
        return "\"" + Integer.toHexString(java.util.Arrays.hashCode(JqValues.serializeToBytes(state))) + "\"";
    }

    private RawResponse async() {
        return async("op-" + nextOperation++, null);
    }

    private static RawResponse async(String id, JqValue metadata) {
        var body = JqObject.ofEntries("type", "async", "status_code", 100, "operation", "/1.0/operations/" + id);
        if (metadata != null) body = body.with("metadata", metadata);
        return new RawResponse(202, JqValues.serializeToBytes(body));
    }

    private static RawResponse badRequest() {
        return badRequest("Invalid devices: refused by FakeIncusDaemon");
    }

    private static RawResponse badRequest(String error) {
        return error(400, error);
    }

    private static RawResponse notFound() {
        return error(404, "Not Found");
    }

    private static RawResponse error(int status, String error) {
        // metadata is null, as Incus sends it, which reads as a null, not a missing value
        var body = JqObject.ofEntries("type", "error", "error", error, "error_code", status, "metadata", JqNull.NULL);
        return new RawResponse(status, JqValues.serializeToBytes(body));
    }

    private void set(String instanceName, String key, String value) {
        instances.put(instanceName, instances.get(instanceName).with(key, JqString.of(value)));
    }

    /**
     * A request body, as strictly as production parses JSON; an empty one reads as an empty
     * object, so a request missing it gets an Incus-like answer rather than a fake's NPE.
     */
    private static JqValue parse(byte[] body) {
        return body == null || body.length == 0 ? JqObject.EMPTY : JqValues.parseStrict(body);
    }

    /** {@code value} if it is an object, else an empty one, as a missing member reads. */
    private static JqObject object(JqValue value) {
        return value instanceof JqObject o ? o : JqObject.EMPTY;
    }

    private static JqValue tree(Map<String, ?> map) {
        return JqValues.fromJavaObject(map);
    }

    /** An object's members as text, in order. */
    private static Map<String, String> strings(JqValue object) {
        var map = new LinkedHashMap<String, String>();
        object.asMap().forEach((k, v) -> map.put(k, v.asText()));
        return map;
    }

    private static Map<String, Map<String, String>> deviceMaps(JqValue devices) {
        var map = new LinkedHashMap<String, Map<String, String>>();
        devices.asMap().forEach((k, v) -> map.put(k, strings(v)));
        return map;
    }
}
