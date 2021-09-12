package io.jsonwebtoken.impl.security;

import io.jsonwebtoken.impl.lang.CheckedFunction;
import io.jsonwebtoken.impl.lang.Converter;
import io.jsonwebtoken.impl.lang.Converters;
import io.jsonwebtoken.lang.Arrays;
import io.jsonwebtoken.lang.Assert;
import io.jsonwebtoken.lang.Collections;
import io.jsonwebtoken.security.MalformedKeyException;
import io.jsonwebtoken.security.RsaPrivateJwk;
import io.jsonwebtoken.security.RsaPublicJwk;
import io.jsonwebtoken.security.UnsupportedKeyException;

import java.math.BigInteger;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAMultiPrimePrivateCrtKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.KeySpec;
import java.security.spec.RSAMultiPrimePrivateCrtKeySpec;
import java.security.spec.RSAOtherPrimeInfo;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class RsaPrivateJwkFactory extends AbstractFamilyJwkFactory<RSAPrivateKey, RsaPrivateJwk> {

    static final Converter<List<RSAOtherPrimeInfo>, Object> RSA_OTHER_PRIMES_CONVERTER =
        Converters.forList(new RSAOtherPrimeInfoConverter());

    private static final String PUBKEY_ERR_MSG = "JwkContext publicKey must be an " + RSAPublicKey.class.getName() + " instance.";

    RsaPrivateJwkFactory() {
        super(DefaultRsaPublicJwk.TYPE_VALUE, RSAPrivateKey.class);
    }

    @Override
    protected boolean supportsKeyValues(JwkContext<?> ctx) {
        return super.supportsKeyValues(ctx) && ctx.containsKey(DefaultRsaPrivateJwk.PRIVATE_EXPONENT);
    }

    private static BigInteger getPublicExponent(RSAPrivateKey key) {
        if (key instanceof RSAPrivateCrtKey) {
            return ((RSAPrivateCrtKey) key).getPublicExponent();
        } else if (key instanceof RSAMultiPrimePrivateCrtKey) {
            return ((RSAMultiPrimePrivateCrtKey) key).getPublicExponent();
        }

        String msg = "Unable to derive RSAPublicKey from RSAPrivateKey implementation [" +
            key.getClass().getName() + "].  Supported keys implement the " +
            RSAPrivateCrtKey.class.getName() + " or " + RSAMultiPrimePrivateCrtKey.class.getName() +
            " interfaces.  If the specified RSAPrivateKey cannot be one of these two, you must explicitly " +
            "provide an RSAPublicKey in addition to the RSAPrivateKey, as the " +
            "[JWA RFC, Section 6.3.2](https://datatracker.ietf.org/doc/html/rfc7518#section-6.3.2) " +
            "requires public values to be present in private RSA JWKs.";
        throw new UnsupportedKeyException(msg);
    }

    private RSAPublicKey derivePublic(final JwkContext<RSAPrivateKey> ctx) {
        RSAPrivateKey key = ctx.getKey();
        BigInteger modulus = key.getModulus();
        BigInteger publicExponent = getPublicExponent(key);
        final RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, publicExponent);
        return generateKey(ctx, RSAPublicKey.class, new CheckedFunction<KeyFactory, RSAPublicKey>() {
            @Override
            public RSAPublicKey apply(KeyFactory kf) {
                try {
                    return (RSAPublicKey) kf.generatePublic(spec);
                } catch (Exception e) {
                    String msg = "Unable to derive RSAPublicKey from RSAPrivateKey {" + ctx + "}.";
                    throw new UnsupportedKeyException(msg);
                }
            }
        });
    }

    @Override
    protected RsaPrivateJwk createJwkFromKey(JwkContext<RSAPrivateKey> ctx) {

        RSAPrivateKey key = ctx.getKey();
        RSAPublicKey rsaPublicKey;

        PublicKey publicKey = ctx.getPublicKey();
        if (publicKey != null) {
            rsaPublicKey = Assert.isInstanceOf(RSAPublicKey.class, publicKey, PUBKEY_ERR_MSG);
        } else {
            rsaPublicKey = derivePublic(ctx);
        }

        // The [JWA Spec](https://datatracker.ietf.org/doc/html/rfc7518#section-6.3.1)
        // requires public values to be present in private JWKs, so add them:
        JwkContext<RSAPublicKey> pubCtx = new DefaultJwkContext<>();
        pubCtx.setKey(rsaPublicKey);
        RsaPublicJwk pubJwk = RsaPublicJwkFactory.DEFAULT_INSTANCE.createJwkFromKey(pubCtx);
        ctx.putAll(pubJwk); // add public values to private key context

        ctx.put(DefaultRsaPrivateJwk.PRIVATE_EXPONENT, encode(key.getPrivateExponent()));

        if (key instanceof RSAPrivateCrtKey) {
            RSAPrivateCrtKey ckey = (RSAPrivateCrtKey) key;
            ctx.put(DefaultRsaPrivateJwk.FIRST_PRIME, encode(ckey.getPrimeP()));
            ctx.put(DefaultRsaPrivateJwk.SECOND_PRIME, encode(ckey.getPrimeQ()));
            ctx.put(DefaultRsaPrivateJwk.FIRST_CRT_EXPONENT, encode(ckey.getPrimeExponentP()));
            ctx.put(DefaultRsaPrivateJwk.SECOND_CRT_EXPONENT, encode(ckey.getPrimeExponentQ()));
            ctx.put(DefaultRsaPrivateJwk.FIRST_CRT_COEFFICIENT, encode(ckey.getCrtCoefficient()));
        } else if (key instanceof RSAMultiPrimePrivateCrtKey) {
            RSAMultiPrimePrivateCrtKey ckey = (RSAMultiPrimePrivateCrtKey) key;
            ctx.put(DefaultRsaPrivateJwk.FIRST_PRIME, encode(ckey.getPrimeP()));
            ctx.put(DefaultRsaPrivateJwk.SECOND_PRIME, encode(ckey.getPrimeQ()));
            ctx.put(DefaultRsaPrivateJwk.FIRST_CRT_EXPONENT, encode(ckey.getPrimeExponentP()));
            ctx.put(DefaultRsaPrivateJwk.SECOND_CRT_EXPONENT, encode(ckey.getPrimeExponentQ()));
            ctx.put(DefaultRsaPrivateJwk.FIRST_CRT_COEFFICIENT, encode(ckey.getCrtCoefficient()));
            List<RSAOtherPrimeInfo> infos = Arrays.asList(ckey.getOtherPrimeInfo());
            if (!Collections.isEmpty(infos)) {
                Object val = RSA_OTHER_PRIMES_CONVERTER.applyTo(infos);
                ctx.put(DefaultRsaPrivateJwk.OTHER_PRIMES_INFO, val);
            }
        }

        return new DefaultRsaPrivateJwk(ctx, pubJwk);
    }

    @Override
    protected RsaPrivateJwk createJwkFromValues(JwkContext<RSAPrivateKey> ctx) {

        final BigInteger privateExponent = getRequiredBigInt(ctx, DefaultRsaPrivateJwk.PRIVATE_EXPONENT, true);

        //The [JWA Spec, Section 6.3.2](https://datatracker.ietf.org/doc/html/rfc7518#section-6.3.2) requires
        //RSA Private Keys to also encode the public key values, so we assert that we can acquire it successfully:
        JwkContext<RSAPublicKey> pubCtx = new DefaultJwkContext<>(ctx, DefaultRsaPrivateJwk.PRIVATE_NAMES);
        RsaPublicJwk pubJwk = RsaPublicJwkFactory.DEFAULT_INSTANCE.createJwkFromValues(pubCtx);
        RSAPublicKey pubKey = pubJwk.toKey();
        final BigInteger modulus = pubKey.getModulus();
        final BigInteger publicExponent = pubKey.getPublicExponent();

        // JWA Section 6.3.2 also indicates that if any of the optional private names are present, then *all* of those
        // optional values must be present (except 'oth', which is handled separately next).  Quote:
        //
        //     If the producer includes any of the other private key parameters, then all of the others MUST
        //     be present, with the exception of "oth", which MUST only be present when more than two prime
        //     factors were used
        //
        boolean containsOptional = false;
        for (String optionalPrivateName : DefaultRsaPrivateJwk.OPTIONAL_PRIVATE_NAMES) {
            if (ctx.containsKey(optionalPrivateName)) {
                containsOptional = true;
                break;
            }
        }

        KeySpec spec;



        if (containsOptional) { //if any one optional field exists, they are all required per JWA Section 6.3.2:
            BigInteger firstPrime = getRequiredBigInt(ctx, DefaultRsaPrivateJwk.FIRST_PRIME, true);
            BigInteger secondPrime = getRequiredBigInt(ctx, DefaultRsaPrivateJwk.SECOND_PRIME, true);
            BigInteger firstCrtExponent = getRequiredBigInt(ctx, DefaultRsaPrivateJwk.FIRST_CRT_EXPONENT, true);
            BigInteger secondCrtExponent = getRequiredBigInt(ctx, DefaultRsaPrivateJwk.SECOND_CRT_EXPONENT, true);
            BigInteger firstCrtCoefficient = getRequiredBigInt(ctx, DefaultRsaPrivateJwk.FIRST_CRT_COEFFICIENT, true);

            // Other Primes Info is actually optional even if the above ones are required:
            if (ctx.containsKey(DefaultRsaPrivateJwk.OTHER_PRIMES_INFO)) {

                Object value = ctx.get(DefaultRsaPrivateJwk.OTHER_PRIMES_INFO);
                List<RSAOtherPrimeInfo> otherPrimes = RSA_OTHER_PRIMES_CONVERTER.applyFrom(value);

                RSAOtherPrimeInfo[] arr = new RSAOtherPrimeInfo[otherPrimes.size()];
                otherPrimes.toArray(arr);

                spec = new RSAMultiPrimePrivateCrtKeySpec(modulus, publicExponent, privateExponent, firstPrime,
                    secondPrime, firstCrtExponent, secondCrtExponent, firstCrtCoefficient, arr);
            } else {
                spec = new RSAPrivateCrtKeySpec(modulus, publicExponent, privateExponent, firstPrime, secondPrime,
                    firstCrtExponent, secondCrtExponent, firstCrtCoefficient);
            }
        } else {
            spec = new RSAPrivateKeySpec(modulus, privateExponent);
        }

        final KeySpec keySpec = spec;
        RSAPrivateKey key = generateKey(ctx, new CheckedFunction<KeyFactory, RSAPrivateKey>() {
            @Override
            public RSAPrivateKey apply(KeyFactory kf) throws Exception {
                return (RSAPrivateKey) kf.generatePrivate(keySpec);
            }
        });
        ctx.setKey(key);

        return new DefaultRsaPrivateJwk(ctx, pubJwk);
    }

    static class RSAOtherPrimeInfoConverter implements Converter<RSAOtherPrimeInfo, Object> {

        @Override
        public Object applyTo(RSAOtherPrimeInfo info) {
            Map<String, String> m = new LinkedHashMap<>(3);
            m.put(DefaultRsaPrivateJwk.PRIME_FACTOR, encode(info.getPrime()));
            m.put(DefaultRsaPrivateJwk.FACTOR_CRT_EXPONENT, encode(info.getExponent()));
            m.put(DefaultRsaPrivateJwk.FACTOR_CRT_COEFFICIENT, encode(info.getCrtCoefficient()));
            return m;
        }

        @Override
        public RSAOtherPrimeInfo applyFrom(Object o) {
            if (o == null) {
                throw new MalformedKeyException("RSA JWK 'oth' Other Prime Info element cannot be null.");
            }
            if (!(o instanceof Map)) {
                String msg = "RSA JWK 'oth' Other Prime Info list must contain map elements of name/value pairs. " +
                    "Element type found: " + o.getClass().getName();
                throw new MalformedKeyException(msg);
            }
            Map<?, ?> m = (Map<?, ?>) o;
            if (Collections.isEmpty(m)) {
                throw new MalformedKeyException("RSA JWK 'oth' Other Prime Info element map cannot be empty.");
            }

            // Need to do add the values to a Context instance to satisfy the API contract of the getRequired* methods
            // below.  It's less than ideal, but it works:
            JwkContext<?> ctx = new DefaultJwkContext<>();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                String name = String.valueOf(entry.getKey());
                ctx.put(name, entry.getValue());
            }

            BigInteger prime = getRequiredBigInt(ctx, DefaultRsaPrivateJwk.PRIME_FACTOR, true);
            BigInteger primeExponent = getRequiredBigInt(ctx, DefaultRsaPrivateJwk.FACTOR_CRT_EXPONENT, true);
            BigInteger crtCoefficient = getRequiredBigInt(ctx, DefaultRsaPrivateJwk.FACTOR_CRT_COEFFICIENT, true);

            return new RSAOtherPrimeInfo(prime, primeExponent, crtCoefficient);
        }
    }
}
