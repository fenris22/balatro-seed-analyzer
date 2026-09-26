Kotlin/OpenCL balatro seed searcher

Heavily utilizes FP64 vector operations,
Will take full advantage of your gpu,
Runs best on MI300X / MI400X variants,
Up to 5 billion seeds per second with complex multi ante conditions on data center hardware

Evententually i might rent some hardware for like 15$ and pick a bunch of conditions and search the entire balatro seed pool in an hour
Once amd releases their MI400 series cards this is gonna be crazy

If you wanna build it yourself you can make your own conditions in main(), there are some sample conditions already there.
I would recommend building a shadow jar so you only have to install java on whatever system you want to run it on, or just run it straight from ur ide


LINUX CLOUD COMPUTE:
I belive CUDA 13.0+ is required for this to work...I don't have an nvidia gpu myself so I have only tested this on nvidia gpus in the cloud.

If your NVIDIA card is not detected on a cloud gpu instance, it's likely that you are missing a part of the OpenCL driver:

1. Check what's there:

ldconfig -p | grep -i opencl
ls /etc/OpenCL/vendors/ 2>/dev/null

The first line should list libnvidia-opencl.so.1, which is NVIDIA's OpenCL driver. If it does, the driver is present and only the loader and pointer file are missing.

2. Add the loader and the pointer file:

apt-get update && apt-get install -y ocl-icd-libopencl1 clinfo
mkdir -p /etc/OpenCL/vendors
echo "libnvidia-opencl.so.1" > /etc/OpenCL/vendors/nvidia.icd
clinfo -l

clinfo -l should now show "NVIDIA CUDA" and your Nvidia GPU. Restart the finder: the page's header chip should show the card, and the log should say "Scanning for GPUs: platform NVIDIA CUDA: 1 usable GPU(s)".


