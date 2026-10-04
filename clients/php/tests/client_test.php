<?php

declare(strict_types=1);

require_once __DIR__ . '/../src/Client.php';

use ZedPkg\Client;

$failed = false;
try {
    new Client('https://user:pass@example.com');
    $failed = true;
} catch (RuntimeException) {
}

if ($failed) {
    fwrite(STDERR, "credential-bearing URL was accepted\n");
    exit(1);
}

new Client('https://example.com/api/');
echo "php client validation passed\n";
