package com.jemcik.jemrec.shell;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * May be null - and NullAway (./gradlew :daemon-tests:errorProne) then checks
 * every use for it, the daemon's whole package. Anything not marked is taken
 * as never null, and assigning it null fails the build.
 *
 * The daemon's own, because what Android ships (android.annotation.Nullable)
 * is not in the public android.jar build.sh compiles against, and a library
 * would be one more thing to download. Source only: nothing of it reaches
 * the class files but this type.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})
@interface Nullable {
}
