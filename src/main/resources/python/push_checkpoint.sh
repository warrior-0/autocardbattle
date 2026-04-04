#!/bin/bash
# Script to push the latest checkpoint to GitHub
cd "$(dirname "$0")/../../../.." || exit

# Add the checkpoint files
git add src/main/resources/python/latest_checkpoint.json
git add src/main/resources/python/checkpoint_q_policy_*.json

# Commit and push
if git commit -m "Auto-save checkpoint: $(date)"; then
    git push origin main
    echo "Checkpoint pushed to GitHub successfully at $(date)"
else
    echo "No changes to commit or failed to push at $(date)"
fi
