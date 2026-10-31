// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry.permission

/**
 * The seven ways a permission can reach a granted state on this device.
 *
 * WHY THIS IS AN ENUM AND NOT A STRING. Every one of these has a different
 * verification method, and a route whose verification is wrong is worse than no
 * route at all: it reports a grant that did not land. Naming the route as a value
 * forces the executor to say how it will confirm the grant before it fires
 * anything, which is the whole contract of #172.
 *
 * A REQUESTED GRANT IS NOT A GRANT THAT LANDED. That sentence is the reason this
 * file exists. Each route below is paired with the read that proves it, in
 * [GrantOrchestrator.verify].
 */
enum class GrantRoute {
    /**
     * The ordinary Android permission dialog. Executed as a broadcast that
     * MainActivity answers, because the dialog needs an Activity and a tool does
     * not have one. Verified by re-reading the grant, never by the dialog closing.
     */
    RUNTIME_DIALOG,

    /**
     * A Settings toggle, such as "Modify system settings" or "Display over other
     * apps". Verified by reading the app-op back, never by the intent starting.
     */
    APP_OP_SETTINGS,

    /**
     * A Settings screen that binds a service rather than granting anything: the
     * accessibility service and the notification listener. Verified by reading the
     * enabled-services list in Settings.Secure.
     */
    SERVICE_SETTINGS,

    /**
     * A platform role such as the dialler, granted through RoleManager and held
     * only while the app owns it. Verified with isRoleHeld.
     */
    ROLE,

    /**
     * Shell UID 2000 through Shizuku, which is how a permission carrying the
     * `development` protection flag reaches a normal app. Shipped in #173; until
     * then this route reports that it is not available rather than pretending.
     */
    SHIZUKU,

    /**
     * Device Owner through managed provisioning. Grants silently with no dialog,
     * which is why it is the preferred route for runtime permissions once the app
     * is the owner. Verified with getPermissionGrantState.
     */
    DEVICE_OWNER,

    /**
     * Nothing to grant. Either the permission is protectionLevel=normal and held
     * from install, or it protects GURU's own service and needs no tool. Verified
     * by confirming it reads as held; a denial here is a build fault.
     */
    SELF_GUARD,
}

/**
 * The device facts a route decision depends on.
 *
 * WHY THIS IS A PARAMETER AND NOT A CALL. Every field below comes from a live
 * Android API, and asking Android from inside the decision would make the
 * decision untestable without a device. Passed in, the rule is provable on the
 * JVM in milliseconds, which is what these facts deserve: they are the same six
 * questions every time and the answers mean the same thing.
 *
 * [hasDevelopmentFlag] is the field that decides whether a privileged permission
 * is obtainable at all, and it is the correction to the batch brief. The Android
 * platform documentation states plainly that a permission is grantable via
 * `pm grant` only when it carries the `development` protection flag. Signature
 * and privileged are not sufficient on their own. GURU reads the flag from the
 * device per permission rather than assuming, because a wrong assumption here
 * sends someone through a Shizuku setup that cannot work.
 */
data class GrantEnvironment(
    /** This app is Device Owner, so setPermissionGrantState is available. */
    val isDeviceOwner: Boolean = false,
    /** Shizuku is installed, running and authorised. False until #173 ships. */
    val shizukuAvailable: Boolean = false,
    /** The permission carries PROTECTION_FLAG_DEVELOPMENT, so `pm grant` works. */
    val hasDevelopmentFlag: Boolean = false,
    /** A role intent can be attempted: API 29+ and the app declares the component. */
    val roleRequestable: Boolean = false,
)

/**
 * The decision for one permission: which route, whether it can succeed, and the
 * one sentence explaining the answer when it cannot.
 *
 * [route] is null when no grant path exists at all, which is a real answer and
 * not an error. Absent hardware and a signature permission with no `development`
 * flag both land here, and both must tell the person the device or platform is
 * the obstacle rather than sending them to a screen that cannot help.
 */
data class GrantPlan(
    val spec: PermissionSpec,
    val route: GrantRoute?,
    /** True when walking [route] can reach a granted state on this device. */
    val canSucceed: Boolean,
    /**
     * Present only when [canSucceed] is false. One sentence naming what stands in
     * the way, so a tool can refuse without guessing.
     */
    val blockedBy: String? = null,
)

/**
 * GrantPolicy — the pure half of the orchestrator.
 *
 * Every branch here is a rule about what Android allows, and every rule has a
 * stated source in the comment beside it. Nothing reads a Context, nothing starts
 * an intent, nothing awaits a broadcast: this decides, [GrantOrchestrator] does.
 * The split is what makes the seven routes testable without a device.
 */
object GrantPolicy {

    /**
     * Decide the route for one permission.
     *
     * ORDER MATTERS. When the app is Device Owner, a runtime permission is granted
     * silently through DevicePolicyManager instead of interrupting the person with
     * a dialog, so the owner check comes before the dialog branch. That is the
     * single biggest quality-of-life difference the owner path buys, and it is why
     * this is not simply a tier-to-route table.
     */
    fun plan(spec: PermissionSpec, env: GrantEnvironment): GrantPlan = when (spec.tier) {

        // Held from install. No dialog exists for one of these, so the honest
        // report is that it is already held. A denial means the manifest lost the
        // declaration, which GrantCheck already words as a build fault.
        PermissionTier.INSTALL -> GrantPlan(spec, GrantRoute.SELF_GUARD, canSucceed = true)

        // A service guard protects GURU's own service rather than granting a
        // capability, so there is nothing for the person to do.
        PermissionTier.SELF_GUARD -> GrantPlan(spec, GrantRoute.SELF_GUARD, canSucceed = true)

        PermissionTier.RUNTIME -> if (env.isDeviceOwner) {
            GrantPlan(spec, GrantRoute.DEVICE_OWNER, canSucceed = true)
        } else {
            GrantPlan(spec, GrantRoute.RUNTIME_DIALOG, canSucceed = true)
        }

        // A Settings toggle. The route is a screen and the verification is the
        // app-op read-back, because the toggle can be switched off again and the
        // screen returning does not mean the value changed.
        PermissionTier.APPOP -> GrantPlan(spec, GrantRoute.APP_OP_SETTINGS, canSucceed = true)

        // A role is held, not granted, and only while the app owns it. When the
        // app declares no qualifying component the request would fail on the
        // platform's terms, so that is reported here rather than discovered by the
        // person tapping a button that cannot work.
        PermissionTier.ROLE -> if (env.roleRequestable) {
            GrantPlan(spec, GrantRoute.ROLE, canSucceed = true)
        } else {
            GrantPlan(
                spec,
                route = GrantRoute.ROLE,
                canSucceed = false,
                blockedBy = "This build does not declare the component that the " +
                    "${spec.label} role requires, so the role cannot be requested yet.",
            )
        }

        PermissionTier.SERVICE -> GrantPlan(spec, GrantRoute.SERVICE_SETTINGS, canSucceed = true)

        // The tier that needed the correction. Signature and privileged alone do
        // not make a permission grantable: the platform documentation states that
        // `pm grant` works only for permissions carrying the `development`
        // protection flag. Without that flag there is no route at all, and saying
        // so is the honest answer rather than pointing at Shizuku forever.
        PermissionTier.PRIVILEGED -> when {
            !env.hasDevelopmentFlag -> GrantPlan(
                spec,
                route = null,
                canSucceed = false,
                blockedBy = "The platform marks the ${spec.label} permission as " +
                    "reserved for system apps, and it is not grantable by any " +
                    "command, so GURU cannot " + spec.consequence + " on this device.",
            )
            // Grantable in principle, but only through the shell UID, which is
            // #173's work. Reporting the wait honestly beats pretending a dialog
            // will appear.
            !env.shizukuAvailable -> GrantPlan(
                spec,
                route = GrantRoute.SHIZUKU,
                canSucceed = false,
                blockedBy = "The ${spec.label} permission can only be granted through " +
                    "Shizuku, which is not set up on this device yet, so GURU cannot " +
                    spec.consequence + ".",
            )
            else -> GrantPlan(spec, GrantRoute.SHIZUKU, canSucceed = true)
        }

        // No permission is involved. The device does not carry the feature, and no
        // grant of any kind changes that.
        PermissionTier.HARDWARE -> GrantPlan(
            spec,
            route = null,
            canSucceed = false,
            blockedBy = "This device does not have the ${spec.label}, so GURU cannot " +
                spec.consequence + " here.",
        )
    }

    /**
     * The routes in the order a person should be offered them, most convenient
     * first. Used by the report a tool gives when it names the ways forward.
     *
     * Self-guard is absent on purpose: it is not something the person does.
     */
    fun orderedRoutes(): List<GrantRoute> = listOf(
        GrantRoute.DEVICE_OWNER,
        GrantRoute.SHIZUKU,
        GrantRoute.RUNTIME_DIALOG,
        GrantRoute.ROLE,
        GrantRoute.APP_OP_SETTINGS,
        GrantRoute.SERVICE_SETTINGS,
    )
}
