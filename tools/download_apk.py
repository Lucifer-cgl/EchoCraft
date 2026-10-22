"""Download the pinned release, trying GitHub before a mirror, with SHA256 verification."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--route', choices=['auto', 'direct', 'mirror'], default='auto')
    parser.add_argument('--output', type=Path, default=ROOT / 'downloads' / 'EchoCraft-debug.apk')
    args = parser.parse_args()
    config = json.loads((ROOT / 'docs/download/release.json').read_text(encoding='utf-8'))
    direct = f"https://github.com/{config['repository']}/releases/download/{config['tag']}/{config['asset']}"
    routes = {'direct': direct, 'mirror': 'https://gh-proxy.com/' + direct}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        raise SystemExit('Output already exists; choose another --output path.')
    temporary = args.output.with_name(args.output.name + '.part')
    if temporary.exists():
        raise SystemExit('Temporary download already exists; choose another --output path.')
    errors = []
    for route in (['direct', 'mirror'] if args.route == 'auto' else [args.route]):
        try:
            print(f'Downloading via {route} ...')
            digest = hashlib.sha256()
            request = urllib.request.Request(routes[route], headers={'User-Agent': 'EchoCraft-Downloader/0.1'})
            size = 0
            with urllib.request.urlopen(request, timeout=12) as response, temporary.open('xb') as out:
                while block := response.read(65536):
                    size += len(block)
                    if size > config['bytes']:
                        raise ValueError('Unexpected download size')
                    digest.update(block)
                    out.write(block)
            if size != config['bytes'] or digest.hexdigest() != config['sha256']:
                raise ValueError('APK checksum mismatch; downloaded file rejected')
            temporary.rename(args.output)
            print(f'Verified SHA256: {digest.hexdigest()}\nSaved: {args.output.resolve()}')
            return
        except Exception as error:
            temporary.unlink(missing_ok=True)
            errors.append(f'{route}: {error}')
    raise SystemExit('\n'.join(errors))


if __name__ == '__main__':
    main()
