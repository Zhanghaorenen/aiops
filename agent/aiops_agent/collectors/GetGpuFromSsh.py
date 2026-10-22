##通过ssh链接服务器

import SSHClient


class GetGpuFromSsh:
    def __init__(self, host, port, username, password):
        self.host = host
        self.port = port
        self.username = username
        self.password = password

    def get_gpu_info(self):
        try:
            with SSHClient.SSHClient(self.host, self.port, self.username, self.password) as ssh:
                command = "nvidia-smi --query-gpu=name,memory.total,memory.used,memory.free,temperature.gpu --format=csv,noheader,nounits"
                output = ssh.execute_command(command)
                output_lines = output.strip().split("\n")
                gpu_info_list = []
                for line in output_lines:
                    name, memory_total, memory_used, memory_free, temperature = line.split(", ")
                    gpu_info_list.append({
                        "name": name,
                        "memory_total": int(memory_total),
                        "memory_used": int(memory_used),
                        "memory_free": int(memory_free),
                        "temperature": int(temperature),
                        "processes": []
                    })
                Process = []
                for gpu in gpu_info_list:
                    command_process = f"nvidia-smi pmon -i {gpu['name']} -c 1"
                    process_output = ssh.execute_command(command_process)
                    process_lines = process_output.strip().split("\n")[2:]  # Skip header lines
                    for process_line in process_lines:
                        if process_line.strip():
                            pid, type_, sm, mem, enc, dec, command_name = process_line.split()
                            Process.append({
                                "pid": int(pid),
                                "type": type_,
                                "sm": int(sm),
                                "mem": int(mem),
                                "enc": int(enc),
                                "dec": int(dec),
                                "command_name": command_name
                            })
                memory_totalInfo = {
                    "gpu_info": gpu_info_list,
                    "process_info": {
                        "processes": Process
                    }
                }
                return memory_totalInfo
        except Exception as e:
            print("获取GPU信息失败:", e)
            return None