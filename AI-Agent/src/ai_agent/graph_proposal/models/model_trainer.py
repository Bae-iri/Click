"""Model training/evaluation boundary."""

from __future__ import annotations


class ModelTrainer:
    def train(self, dataset_version: str, model_type: str) -> dict:
        return {
            "model_type": model_type,
            "training_dataset_version": dataset_version,
            "deployment_status": "candidate",
        }
