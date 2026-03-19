import gym
import numpy as np

class AITrainer:
    def __init__(self, env_name):
        self.env = gym.make(env_name)
        self.q_table = np.zeros([self.env.observation_space.n, self.env.action_space.n])
        self.learning_rate = 0.1
        self.discount_factor = 0.95
        self.exploration_rate = 1.0
        self.exploration_decay = 0.995
        self.min_exploration_rate = 0.1

    def choose_action(self, state):
        if np.random.rand() < self.exploration_rate:
            return self.env.action_space.sample()  # Explore
        else:
            return np.argmax(self.q_table[state])  # Exploit

    def train(self, episodes):
        for episode in range(episodes):
            state = self.env.reset()
            done = False
            while not done:
                action = self.choose_action(state)
                next_state, reward, done, _ = self.env.step(action)
                old_value = self.q_table[state, action]
                next_max = np.max(self.q_table[next_state])
                # Update Q-value
                new_value = (1 - self.learning_rate) * old_value + self.learning_rate * (reward + self.discount_factor * next_max)
                self.q_table[state, action] = new_value
                state = next_state
            self.exploration_rate = max(self.min_exploration_rate, self.exploration_rate * self.exploration_decay)

    def save_model(self, filename):
        np.save(filename, self.q_table)

    def load_model(self, filename):
        self.q_table = np.load(filename)

if __name__ == '__main__':
    trainer = AITrainer('Taxi-v3')
    trainer.train(10000)
    trainer.save_model('q_table.npy')
