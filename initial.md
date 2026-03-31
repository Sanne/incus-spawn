# **Secure Identity Isolation on Fedora using Incus**

This guide explains how to set up a secure, hardware-isolated workspace on Fedora using Incus (System Containers). Unlike standard Docker or Podman, this setup provides a strict security boundary for a secondary "Work" identity while still allowing graphical applications (like a web browser) to run smoothly at native speeds.

## **Prerequisites**

* A host machine running Fedora.  
* You must be the primary user on the machine (which uses the standard User ID `1000`).

---

## **Phase 1: Install and Initialize Incus**

First, we need to install the container runtime and set up its basic networking. Open your standard host terminal and run the following commands:

**1. Install Incus and enable the service:**

```Bash
sudo dnf install incus  
sudo systemctl enable --now incus
```

**2. Grant your user permission to run Incus commands (without typing sudo):**

```Bash
sudo usermod -aG incus-admin $USER  
newgrp incus-admin
```

**3. Initialize the default environment:** *(Press Enter if it prompts you for any defaults)*

```Bash
incus admin init --minimal
```

**4. Whitelist the Incus network bridge in the Fedora firewall:** *(This crucial step ensures your container can access the internet and resolve domain names).*

```Bash
sudo firewall-cmd --zone=trusted --change-interface=incusbr0 --permanent  
sudo firewall-cmd --reload
```

---

## **Phase 2: Configure Permissions and Launch the Container**

Wayland (Fedora's display system) is highly secure. To allow a graphical app inside the container to show up on your host screen, we must perfectly map your host User ID (`1000`) to the container.

**1. Tell your host OS to allocate the necessary User IDs for Incus:**

```Bash
echo "root:1000:1" | sudo tee -a /etc/subuid /etc/subgid  
echo "root:1000000:65536" | sudo tee -a /etc/subuid /etc/subgid  
sudo systemctl restart incus
```

**2. Launch a fresh Fedora 43 container named `qwork`:** 

```  
incus launch images:fedora/43 qwork
```

**3. Apply the ID mapping and pass through the graphics hardware:**

```Bash
incus config set qwork raw.idmap "both 1000 1000"  
incus config device add qwork mygpu gpu  
incus config device add qwork wayland disk source=/run/user/1000/wayland-0 path=/mnt/wayland-0
```

**4. Restart the container to apply all hardware changes:**

```Bash  
incus restart qwork
```
---

## **Phase 3: Configure the Isolated Work Environment**

Now we will enter the container, install our tools, and create the isolated `quser` account.

**1. Enter the container as the root user:**

```Bash
incus shell qwork
```

**2. Install Firefox and create the `quser` account:**

```Bash  
dnf install -y firefox  
useradd -m -u 1000 quser
```
**3. Switch to the new `quser` account:**

```Bash  
su - quser
```

**4. Create a persistent bridge for the Wayland display:** Because temporary folders are wiped when containers restart, we will store the display socket safely in the user's home directory. Run these commands to configure the environment permanently:

```Bash
# Create a secure, hidden runtime folder  
mkdir -p ~/.run  
chmod 0700 ~/.run

# Add the configuration to your bash profile so it loads automatically  
echo 'export XDG_RUNTIME_DIR=~/.run' >> ~/.bashrc  
echo 'export WAYLAND_DISPLAY=wayland-0' >> ~/.bashrc  
echo 'ln -sf /mnt/wayland-0 ~/.run/wayland-0' >> ~/.bashrc

# Apply the profile changes immediately  
source ~/.bashrc
```

---

## **Phase 4: Using Your Isolated Environment**

Your setup is now complete!

You can open a shell using your new, sandboxed user:

```Bash
incus exec qwork -- su - quser
```


To launch your fully isolated work browser at any time, run the following command from your host terminal:

```Bash
incus exec qwork -- su - quser -c "firefox"
```

* **Filesystem:** Firefox is saving cookies, history, and downloads completely inside the `qwork` container.  
* **Network:** It has a distinct internal IP address.  
* **Performance:** Because we passed the GPU through, it runs with full hardware acceleration.
