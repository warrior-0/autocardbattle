import numpy as np

class GameAI:
    """
    PyTorch 의존성을 제거한 NumPy 기반 GameAI 클래스.
    RAM 512MB 환경에서 안정적으로 동작하도록 설계되었습니다.
    """
    def __init__(self, input_size=64, hidden_size=128, output_size=64):
        # Xavier/He Initialization
        self.w1 = np.random.randn(hidden_size, input_size) * np.sqrt(2.0 / input_size)
        self.b1 = np.zeros((hidden_size, 1))
        self.w2 = np.random.randn(output_size, hidden_size) * np.sqrt(2.0 / hidden_size)
        self.b2 = np.zeros((output_size, 1))

    def relu(self, x):
        return np.maximum(0, x)

    def forward(self, x):
        """
        x: numpy array of shape (input_size,) or (batch, input_size)
        """
        if x.ndim == 1:
            x = x.reshape(-1, 1)
        elif x.ndim == 2 and x.shape[1] == self.w1.shape[1]:
            x = x.T
            
        z1 = np.dot(self.w1, x) + self.b1
        a1 = self.relu(z1)
        z2 = np.dot(self.w2, a1) + self.b2
        
        # 결과를 다시 (batch, output_size) 형태로 반환
        return z2.T

    def choose_action(self, board):
        """
        board: 8x8 numpy array
        returns: int index of chosen action
        """
        # 입력 데이터 평탄화 및 forward 전파
        board_flat = board.flatten()
        output = self.forward(board_flat)
        
        # 가장 높은 Q-value를 가진 인덱스 반환
        action = int(np.argmax(output, axis=1)[0])
        return action

    def load_state_dict(self, sd):
        """
        PyTorch state_dict 형식의 데이터를 NumPy 배열로 로드합니다.
        """
        def to_np(v):
            if hasattr(v, "detach"): # torch.Tensor
                return v.detach().cpu().numpy()
            return np.array(v)
            
        # 기존 PyTorch 레이어 이름(fc1, fc2)에 맞춰 매핑
        if "fc1.weight" in sd:
            self.w1 = to_np(sd["fc1.weight"])
            self.b1 = to_np(sd["fc1.bias"]).reshape(-1, 1)
            self.w2 = to_np(sd["fc2.weight"])
            self.b2 = to_np(sd["fc2.bias"]).reshape(-1, 1)
        # 또는 DQNNetwork 형식(net.0, net.2, net.4)인 경우 (구조가 다를 수 있으므로 주의)
        elif "net.0.weight" in sd:
            self.w1 = to_np(sd["net.0.weight"])
            self.b1 = to_np(sd["net.0.bias"]).reshape(-1, 1)
            # GameAI는 2개 레이어만 있으므로 net.4를 w2로 매핑하거나 구조에 맞춰 수정 필요
            self.w2 = to_np(sd["net.4.weight"])
            self.b2 = to_np(sd["net.4.bias"]).reshape(-1, 1)
