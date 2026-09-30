"""Seed the FacePsy study configuration into Cloud Firestore.

Each top-level key of the config JSON file becomes one document in the `config`
collection (e.g. `triggers`, `triggerDuration`, `stroopTask`, `survey`); existing
documents with the same id are overwritten. See docs/data-schema.md for the keys the
app reads.

Usage:
    python configure_firebase.py [--cred ./cred/filename.json] [--config config.json]
"""
import argparse
import json

import firebase_admin
from firebase_admin import credentials, firestore


def upload_collection(db, collection_name, json_file):
    """Writes every top-level entry of `json_file` as a document of `collection_name`."""
    with open(json_file, "r") as f:
        data = json.load(f)

    for doc_id, doc_data in data.items():
        db.collection(collection_name).document(doc_id).set(doc_data)

    print(f"Successfully uploaded {len(data)} documents to {collection_name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--cred",
        default="./cred/filename.json",
        help="Firebase service-account key JSON (default: ./cred/filename.json)",
    )
    parser.add_argument(
        "--config",
        default="config.json",
        help="Config JSON to upload (default: config.json)",
    )
    args = parser.parse_args()

    # Credentials of the target Firebase project
    cred = credentials.Certificate(args.cred)
    firebase_admin.initialize_app(cred)
    db = firestore.client()

    # Upload the study configuration to the "config" collection
    upload_collection(db, "config", args.config)


if __name__ == "__main__":
    main()
