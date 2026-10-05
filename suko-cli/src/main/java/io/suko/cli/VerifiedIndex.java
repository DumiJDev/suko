package io.suko.cli;

import io.suko.registry.FileSystemRegistrySource;
import io.suko.registry.RegistryIndex;
import io.suko.registry.RegistryJson;
import io.suko.registry.RegistryJsonException;
import io.suko.registry.RegistrySecurityException;
import io.suko.registry.RegistrySignature;
import io.suko.registry.RegistrySource;
import io.suko.registry.TrustedKeys;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Carrega o índice do registry e verifica-o (spec do subprojeto 14, M6) antes de qualquer outra coisa.
 * <p>
 * Ordem estrita, que não se negocia:
 * </p>
 * <ol>
 *   <li>{@code --allow-unsigned} fora de um registry local é recusado logo à entrada;</li>
 *   <li>a assinatura é verificada sobre os <strong>bytes exatos</strong> de {@code registry.json}, contra o
 *       {@code registryId} <strong>canónico da configuração</strong> ({@link #canonicalId}), nunca contra o
 *       {@code registryId} declarado pelo próprio índice que está a ser verificado;</li>
 *   <li>só depois o parse; {@code schemaVersion} tem de ser exatamente 2;</li>
 *   <li>{@code issuedAt}/{@code expires} são lidos como {@link Instant} (ilegível = falha);
 *       {@code expires} passado = {@code REGISTRY_EXPIRED};</li>
 *   <li>assinado: {@code registryId}/{@code ref} têm de bater com a configuração; anti-rollback contra o lockfile;</li>
 *   <li>cada entrada: {@code manifestSha256} com 64 hex minúsculos e {@code manifest} um caminho relativo sem
 *       truques (o hash dos bytes de cada manifesto é comparado no {@link Resolver}, antes do parse).</li>
 * </ol>
 * <p>
 * Códigos (prefixo da mensagem da {@link CliException}): {@code REGISTRY_UNSIGNED}, {@code REGISTRY_BAD_SIGNATURE},
 * {@code REGISTRY_MISMATCH}, {@code REGISTRY_EXPIRED}, {@code REGISTRY_ROLLBACK}, {@code REGISTRY_MANIFEST_HASH} e
 * {@code REGISTRY_INVALID} (índice estruturalmente inaceitável: esquema, datas, caminho de manifesto).
 * </p>
 */
public final class VerifiedIndex {

    /** O {@code registryId} do registry oficial (o que vai dentro do índice assinado). */
    public static final String OFFICIAL_REGISTRY_ID = "https://raw.githubusercontent.com/DumiJDev/suko/";
    /** O URL base do registry oficial para uma ref ({@code %s}); é o que {@code suko init} grava por omissão. */
    public static final String OFFICIAL_BASE_TEMPLATE = OFFICIAL_REGISTRY_ID + "%s/suko-components/";

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    /**
     * Caminho de manifesto aceite: segmentos {@code [A-Za-z0-9._-]} separados por {@code /}, nenhum vazio e nenhum a
     * começar por {@code .} (exclui {@code .}, {@code ..} e ficheiros ocultos). Sem {@code :} (esquemas, letras de
     * unidade), sem {@code \}, sem {@code %} (traversal codificado), sem {@code ?}/{@code #}.
     */
    private static final Pattern MANIFEST_PATH =
        Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*");

    public record Result(RegistryIndex index, boolean signed, String keyId) {
    }

    private VerifiedIndex() {
    }

    /**
     * Identidade canónica de um registry configurado: o {@code registryId} oficial se {@code base} for exatamente o
     * URL oficial para {@code ref} (a ref está no meio do URL, por isso o URL em si nunca podia ser igual ao
     * {@code registryId} assinado); senão o próprio {@code base}, normalizado com um {@code /} final.
     */
    public static String canonicalId(String base, String ref) {
        String normalized = base.endsWith("/") ? base : base + "/";
        if (ref != null && normalized.equals(String.format(OFFICIAL_BASE_TEMPLATE, ref))) {
            return OFFICIAL_REGISTRY_ID;
        }
        return normalized;
    }

    public static Result load(RegistrySource source, String registryBase, String registryRef, Optional<Lockfile> lock,
                              boolean allowUnsigned, boolean allowDowngrade, TrustedKeys extraKeys, PrintStream warn) {
        boolean local = isLocal(source, registryBase);
        // A identidade vem SEMPRE da configuração/URL do utilizador, nunca do índice a verificar.
        String registryId = canonicalId(registryBase, registryRef);

        if (allowUnsigned && !local) {
            throw new CliException("REGISTRY_UNSIGNED: --allow-unsigned só é aceite para registries locais "
                + "(caminho, localhost, 127.0.0.1, [::1]); \"" + registryBase + "\" não é local.");
        }

        byte[] indexBytes;
        try {
            indexBytes = source.resolve("registry.json");
        } catch (IOException e) {
            throw new CliException("Could not read registry.json from \"" + registryBase + "\": " + e.getMessage());
        }
        byte[] sigBytes;
        String sigProblem = null;
        try {
            sigBytes = source.resolve("registry.json.sig");
        } catch (NoSuchFileException e) {
            sigBytes = null;
        } catch (IOException | RuntimeException e) {
            // Qualquer falha a obter a assinatura conta como "sem assinatura" — que é sempre recusado,
            // salvo --allow-unsigned num registry local nunca visto assinado (falha fechada).
            sigBytes = null;
            sigProblem = e.getMessage();
        }

        Optional<Lockfile.Registry> seen = lock.map(Lockfile::registry)
            .filter(r -> r.base() != null && canonicalId(r.base(), r.ref()).equals(registryId));
        boolean signed = sigBytes != null;
        String keyId = null;

        // 1. Assinatura sobre os bytes exatos, antes de qualquer parse.
        if (signed) {
            TrustedKeys keys = Trust.keys().plus(extraKeys);
            try {
                keyId = RegistrySignature.verify(indexBytes, sigBytes, keys, registryId).keyId();
            } catch (RegistrySecurityException e) {
                String hint = keys.forRegistry(registryId).isEmpty()
                    ? " Não há nenhuma chave pública confiável para \"" + registryId + "\": configure-a em suko.json "
                        + "(registry.publicKeys: [{\"keyid\": ..., \"publicKey\": ...}])."
                    : "";
                throw new CliException(e.getMessage() + hint);
            }
        } else {
            String why = sigProblem == null ? "" : " (não foi possível ler registry.json.sig: " + sigProblem + ")";
            if (seen.isPresent() && seen.get().signed()) {
                throw new CliException("REGISTRY_UNSIGNED: o registry \"" + registryId
                    + "\" já foi visto assinado (suko.lock.json) e agora não tem assinatura" + why
                    + " — possível ataque de remoção; --allow-unsigned não é aceite aqui.");
            }
            if (!allowUnsigned) {
                throw new CliException("REGISTRY_UNSIGNED: o registry \"" + registryBase + "\" não tem registry.json.sig" + why + "."
                    + (local ? " Para um registry local de desenvolvimento use --allow-unsigned."
                             : " --allow-unsigned só é aceite para registries locais (caminho, localhost, 127.0.0.1)."));
            }
            warn.println("WARNING: o registry \"" + registryBase
                + "\" está sem assinatura (--allow-unsigned); o conteúdo não é autenticado.");
        }

        // 2. Parse, e só o esquema 2.
        RegistryIndex index;
        try {
            index = RegistryJson.readIndex(new String(indexBytes, StandardCharsets.UTF_8));
        } catch (RegistryJsonException e) {
            throw new CliException("REGISTRY_INVALID: could not parse registry.json from \"" + registryBase + "\": "
                + e.getMessage());
        }
        if (index.schemaVersion() != RegistryIndex.SCHEMA_VERSION) {
            throw new CliException("REGISTRY_INVALID: registry.json from \"" + registryBase + "\" has schemaVersion "
                + index.schemaVersion() + "; this suko CLI only accepts schemaVersion " + RegistryIndex.SCHEMA_VERSION + ".");
        }

        // 3. Datas (ilegível = falha) e validade.
        Instant issuedAt = instant(index.issuedAt(), "issuedAt");
        if (index.expires() != null && !instant(index.expires(), "expires").isAfter(Instant.now())) {
            throw new CliException("REGISTRY_EXPIRED: o índice de \"" + registryBase + "\" expirou em " + index.expires()
                + ". Atualize a ref ou peça ao registry um índice assinado novo.");
        }

        // 4. Identidade e anti-rollback (só têm significado com assinatura).
        if (signed) {
            if (!registryId.equals(index.registryId())) {
                throw new CliException("REGISTRY_MISMATCH: o índice assinado é do registry \"" + index.registryId()
                    + "\", não de \"" + registryId + "\".");
            }
            if (!registryRef.equals(index.ref())) {
                throw new CliException("REGISTRY_MISMATCH: o índice assinado é da ref \"" + index.ref()
                    + "\", mas foi pedida \"" + registryRef + "\".");
            }
            if (seen.isPresent() && seen.get().signed() && seen.get().issuedAt() != null && !allowDowngrade) {
                Instant before = instant(seen.get().issuedAt(), "issuedAt (" + Lockfile.FILE_NAME + ")");
                boolean olderIssue = issuedAt.isBefore(before);
                boolean lowerVersion = seen.get().registryVersion() != null
                    && compareVersions(index.registryVersion(), seen.get().registryVersion()) < 0;
                if (olderIssue || lowerVersion) {
                    throw new CliException("REGISTRY_ROLLBACK: o índice (" + index.issuedAt() + ", versão "
                        + index.registryVersion() + ") é mais antigo do que o já instalado (" + seen.get().issuedAt()
                        + ", versão " + seen.get().registryVersion() + "). Use --allow-downgrade se for intencional.");
                }
            }
        }

        // 5. Entradas: formato do hash e caminho do manifesto.
        for (RegistryIndex.Entry entry : index.components()) {
            if (entry.manifestSha256() == null || !SHA256_HEX.matcher(entry.manifestSha256()).matches()) {
                throw new CliException("REGISTRY_MANIFEST_HASH: a entrada \"" + entry.name()
                    + "\" do índice não tem um manifestSha256 válido (64 hex minúsculos): " + entry.manifestSha256());
            }
            if (!isSafeManifestPath(entry.manifest())) {
                throw new CliException("REGISTRY_INVALID: a entrada \"" + entry.name()
                    + "\" do índice aponta para um manifesto fora do registry ou com um caminho inválido: \""
                    + entry.manifest() + "\"");
            }
        }
        return new Result(index, signed, keyId);
    }

    /** Caminho relativo seguro para um manifesto (ver {@link #MANIFEST_PATH}). */
    static boolean isSafeManifestPath(String path) {
        return path != null && MANIFEST_PATH.matcher(path).matches();
    }

    public static boolean isLocal(RegistrySource source, String base) {
        if (source instanceof FileSystemRegistrySource) {
            return true;
        }
        for (String prefix : new String[] {"http://", "https://"}) {
            if (base.regionMatches(true, 0, prefix, 0, prefix.length())) {
                String rest = base.substring(prefix.length());
                int at = rest.indexOf('@');
                int slash = rest.indexOf('/');
                if (at >= 0 && (slash < 0 || at < slash)) {
                    return false; // userinfo ("http://localhost@evil/") — nunca local
                }
                String host;
                if (rest.startsWith("[")) {
                    int close = rest.indexOf(']');
                    host = close < 0 ? rest : rest.substring(0, close + 1);
                } else {
                    host = rest.split("[:/?#]", 2)[0];
                }
                return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
            }
        }
        return !base.contains("://");
    }

    private static Instant instant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new CliException("REGISTRY_INVALID: o campo " + field + " não é uma data ISO-8601 (UTC): " + value);
        }
    }

    /**
     * Compara versões {@code x.y.z[-pre]}: núcleo numérico campo a campo (em falta = 0); com o mesmo núcleo, uma
     * pré-release ordena antes da final; entre pré-releases, identificadores separados por {@code .}, numéricos
     * por valor e os restantes por ordem lexical (SemVer 2.0 §11).
     */
    static int compareVersions(String a, String b) {
        String[] sa = a.split("-", 2);
        String[] sb = b.split("-", 2);
        int c = compareIdentifiers(sa[0].split("\\."), sb[0].split("\\."), true);
        if (c != 0) {
            return c;
        }
        boolean preA = sa.length > 1;
        boolean preB = sb.length > 1;
        if (preA != preB) {
            return preA ? -1 : 1;
        }
        return preA ? compareIdentifiers(sa[1].split("\\."), sb[1].split("\\."), false) : 0;
    }

    private static int compareIdentifiers(String[] pa, String[] pb, boolean padWithZero) {
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            if (!padWithZero && (i >= pa.length || i >= pb.length)) {
                return i >= pa.length ? -1 : 1;
            }
            String x = i < pa.length ? pa[i] : "0";
            String y = i < pb.length ? pb[i] : "0";
            boolean nx = !x.isEmpty() && x.chars().allMatch(Character::isDigit);
            boolean ny = !y.isEmpty() && y.chars().allMatch(Character::isDigit);
            int c;
            if (nx && ny) {
                c = new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y));
            } else if (nx != ny) {
                c = nx ? -1 : 1;
            } else {
                c = x.compareTo(y);
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
