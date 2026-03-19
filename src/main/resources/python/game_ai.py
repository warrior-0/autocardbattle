import subprocess

class GameAI:
    def __init__(self):
        self.process = None

    def start_engine(self):
        self.process = subprocess.Popen(['python', 'path_to_engine.py'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def get_response(self, input_data):
        if self.process:
            self.process.stdin.write((input_data + '\n').encode())
            self.process.stdin.flush()
            response = self.process.stdout.readline().decode()
            return response.strip()
        return None

    def stop_engine(self):
        if self.process:
            self.process.terminate()
            self.process = None
