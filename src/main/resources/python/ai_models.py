import torch
import torch.nn as nn

class GameAINetwork(nn.Module):
    def __init__(self):
        super(GameAINetwork, self).__init__()
        self.fc1 = nn.Linear(10, 50)  # Example input size of 10
        self.fc2 = nn.Linear(50, 20)
        self.fc3 = nn.Linear(20, 1)   # Example output size of 1
        
    def forward(self, x):
        x = torch.relu(self.fc1(x))
        x = torch.relu(self.fc2(x))
        x = self.fc3(x)
        return x
