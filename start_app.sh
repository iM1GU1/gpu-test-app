#!/usr/bin/env bash
# setup.sh - instala todo lo necesario para el Banco de validacion GPU
# en esta maquina. Se puede volver a ejecutar sin problema: cada paso
# comprueba primero si ya esta hecho antes de repetirlo.
#
# Uso: bash setup.sh

set -uo pipefail
GREEN='\033[0;32m'; YELLOW='\033[0;33m'; NC='\033[0m'
step(){ echo -e "\n${GREEN}==> $1${NC}"; }
warn(){ echo -e "${YELLOW}   aviso: $1${NC}"; }

step "Comprobando si hay una GPU NVIDIA fisicamente instalada"
if lspci | grep -qi nvidia; then
  lspci | grep -i nvidia | sed 's/^/   /'
  GPU_PRESENT=1
else
  warn "No se detecta ninguna tarjeta NVIDIA ahora mismo."
  warn "Sigo instalando el resto del software; el driver se instala solo cuando haya tarjeta metida."
  GPU_PRESENT=0
fi

step "Utilidades base"
sudo apt-get update -y
sudo apt-get install -y nvidia-cuda-toolkit lm-sensors unzip git wget build-essential

if [ "$GPU_PRESENT" = "1" ] && ! command -v nvidia-smi >/dev/null 2>&1; then
  step "Instalando el driver de NVIDIA"
  sudo ubuntu-drivers install || warn "instalalo a mano: sudo apt install nvidia-driver-XXX"
  echo -e "${YELLOW}Reinicia con 'sudo reboot' y vuelve a ejecutar 'bash setup.sh' para continuar.${NC}"
  exit 0
elif command -v nvidia-smi >/dev/null 2>&1; then
  echo "   nvidia-smi ya esta disponible, seguimos."
fi

step "DCGM"
if ! command -v dcgmi >/dev/null 2>&1; then
  UBUNTU_VER=$(lsb_release -rs | tr -d '.')
  wget -q -O /tmp/cuda-keyring.deb "https://developer.download.nvidia.com/compute/cuda/repos/ubuntu${UBUNTU_VER}/x86_64/cuda-keyring_1.1-1_all.deb" \
    && sudo dpkg -i /tmp/cuda-keyring.deb && sudo apt-get update -y \
    && sudo apt-get install -y datacenter-gpu-manager \
    && sudo systemctl enable nvidia-dcgm --now \
    || warn "no se pudo instalar DCGM automaticamente para Ubuntu ${UBUNTU_VER}"
else
  echo "   dcgmi ya estaba instalado."
fi

step "gpu-burn"
if [ ! -x "./gpu-burn/gpu_burn" ]; then
  git clone -q https://github.com/wilicc/gpu-burn.git && (cd gpu-burn && make) \
    || warn "no se pudo compilar gpu-burn"
else
  echo "   gpu-burn ya estaba compilado."
fi

step "memtest_vulkan"
if [ ! -x "./memtest_vulkan/memtest_vulkan" ]; then
  mkdir -p memtest_vulkan
  wget -q -O /tmp/memtest_vulkan.tar.gz \
    https://github.com/GpuZelenograd/memtest_vulkan/releases/latest/download/memtest_vulkan-linux-x86_64.tar.gz \
    && tar -xzf /tmp/memtest_vulkan.tar.gz -C memtest_vulkan \
    || warn "descargalo a mano desde github.com/GpuZelenograd/memtest_vulkan/releases"
else
  echo "   memtest_vulkan ya estaba descargado."
fi

step "Phoronix Test Suite"
if ! command -v phoronix-test-suite >/dev/null 2>&1; then
  sudo apt-get install -y php-cli php-xml phoronix-test-suite \
    || warn "instalalo a mano desde phoronix-test-suite.com"
else
  echo "   phoronix-test-suite ya estaba instalado."
fi

step "Entorno Python de la app"
python3 -m venv venv
source venv/bin/activate
pip install -q -r requirements.txt

step "Listo"
echo "Para arrancar: source venv/bin/activate && python3 app.py"
echo "Luego abre http://$(hostname -I | awk '{print $1}'):8080"
