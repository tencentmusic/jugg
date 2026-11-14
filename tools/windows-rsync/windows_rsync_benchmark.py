"""Small, hash-checked SSH transport workloads; no Gradle build or real APKs."""
import hashlib
import shlex
import statistics
import time


def benchmark(probe, rounds):
    source = probe.output / 'benchmark source'
    source.mkdir()
    for index in range(256):
        (source / f'file-{index:04}.bin').write_bytes(hashlib.sha256(str(index).encode()).digest() * 128)
    (source / 'medium.bin').write_bytes(bytes(range(256)) * 8192)
    local = probe.local(source) + '/'
    remote = probe.config['target'] + ':' + probe.remote + '/benchmark/'
    samples = {name: [] for name in ('initial_push', 'unchanged_push', 'changed_push', 'artifact_pull')}
    for iteration in range(rounds):
        probe.remote_command('rm -rf -- ' + shlex.quote(probe.remote + '/benchmark'))
        download = probe.output / f'benchmark-download-{iteration}'
        download.mkdir()
        timed(probe, samples['initial_push'], ['-a'], local, remote)
        timed(probe, samples['unchanged_push'], ['-a'], local, remote)
        # Change sizes so quick-check detects updates without relying on timestamp resolution.
        for index in range(16):
            with (source / f'file-{index:04}.bin').open('ab') as stream:
                stream.write(b'changed')
        timed(probe, samples['changed_push'], ['-a'], local, remote)
        timed(probe, samples['artifact_pull'], ['-a'], remote, probe.local(download) + '/')
        assert hashes(source) == hashes(download)
    return dict(rounds=rounds, file_count=257, initial_bytes=3 * 1024 * 1024,
                timing_scope='rsync process including SSH startup; local path conversion excluded',
                verified='SHA-256 of every downloaded file after each round',
                workloads={name: dict(seconds=values, median_seconds=round(statistics.median(values), 4),
                                     min_seconds=round(min(values), 4), max_seconds=round(max(values), 4))
                           for name, values in samples.items()})


def timed(probe, samples, arguments, source, destination):
    start = time.perf_counter()
    probe.transfer(arguments, source, destination)
    samples.append(round(time.perf_counter() - start, 4))


def hashes(root):
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in root.iterdir()}
