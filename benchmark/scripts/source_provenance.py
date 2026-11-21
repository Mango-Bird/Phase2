import argparse
import fnmatch
import hashlib
import os
import sys
from pathlib import Path


BUILD_CONTEXT_FILES = (
    '.dockerignore', 'Dockerfile', 'gradlew', 'build.gradle', 'settings.gradle'
)
BUILD_CONTEXT_DIRS = ('gradle', 'src')


def dockerignore_patterns(context):
    patterns = []
    for line in (context / '.dockerignore').read_text(encoding='utf-8').splitlines():
        pattern = line.strip()
        if pattern and not pattern.startswith('#'):
            if pattern.startswith('!'):
                raise ValueError('negated .dockerignore patterns are not supported by fingerprinting')
            patterns.append(pattern.rstrip('/'))
    return patterns


def is_ignored(relative, patterns):
    relative_text = relative.as_posix()
    for pattern in patterns:
        if '/' in pattern:
            if fnmatch.fnmatch(relative_text, pattern):
                return True
        elif any(fnmatch.fnmatch(part, pattern) for part in relative.parts):
            return True
    return False


def source_files(repo_root):
    context = Path(repo_root).resolve() / 'digitalwallet-system'
    patterns = dockerignore_patterns(context)
    files = []
    for name in BUILD_CONTEXT_FILES:
        path = context / name
        if not path.is_file():
            raise ValueError(f'missing Docker build input: {path}')
        files.append(path)
    for name in BUILD_CONTEXT_DIRS:
        directory = context / name
        if not directory.is_dir():
            raise ValueError(f'missing Docker build input directory: {directory}')
        files.extend(
            path for path in directory.rglob('*')
            if path.is_file() and not is_ignored(path.relative_to(context), patterns)
        )
    return context, sorted(files, key=lambda path: path.relative_to(context).as_posix())


def fingerprint(repo_root):
    context, files = source_files(repo_root)
    digest = hashlib.sha256()
    for path in files:
        relative = path.relative_to(context).as_posix().encode('utf-8')
        content = path.read_bytes()
        digest.update(len(relative).to_bytes(8, 'big'))
        digest.update(relative)
        digest.update(len(content).to_bytes(8, 'big'))
        digest.update(content)
    return digest.hexdigest()


def provenance_errors(expected_revision, expected_fingerprint, built_image_id,
                      running_image_id, image_revision, image_fingerprint):
    errors = []
    required = {
        'expected revision': expected_revision,
        'expected source fingerprint': expected_fingerprint,
        'built image ID': built_image_id,
        'running image ID': running_image_id,
        'image revision label': image_revision,
        'image source fingerprint label': image_fingerprint,
    }
    errors.extend(f'missing {name}' for name, value in required.items() if not value)
    if built_image_id and running_image_id and built_image_id != running_image_id:
        errors.append(
            f'stale running image: built {built_image_id}, running {running_image_id}')
    if expected_revision and image_revision and expected_revision != image_revision:
        errors.append(
            f'revision label mismatch: expected {expected_revision}, image has {image_revision}')
    if (expected_fingerprint and image_fingerprint
            and expected_fingerprint != image_fingerprint):
        errors.append(
            'source fingerprint mismatch: expected '
            f'{expected_fingerprint}, image has {image_fingerprint}')
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest='command', required=True)

    fingerprint_parser = subparsers.add_parser('fingerprint')
    fingerprint_parser.add_argument('--repo-root', required=True)

    verify_parser = subparsers.add_parser('verify')
    verify_parser.add_argument('--expected-revision', required=True)
    verify_parser.add_argument('--expected-fingerprint', required=True)
    verify_parser.add_argument('--built-image-id', required=True)
    verify_parser.add_argument('--running-image-id', required=True)
    verify_parser.add_argument('--image-revision', required=True)
    verify_parser.add_argument('--image-fingerprint', required=True)

    args = parser.parse_args(argv)
    if args.command == 'fingerprint':
        print(fingerprint(args.repo_root))
        return 0

    errors = provenance_errors(
        args.expected_revision,
        args.expected_fingerprint,
        args.built_image_id,
        args.running_image_id,
        args.image_revision,
        args.image_fingerprint,
    )
    if errors:
        for error in errors:
            print(f'SUT provenance verification failed: {error}', file=sys.stderr)
        return 2
    print('SUT provenance verified')
    return 0


if __name__ == '__main__':
    sys.exit(main())
