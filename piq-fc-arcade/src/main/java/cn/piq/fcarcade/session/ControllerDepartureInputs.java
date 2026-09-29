package cn.piq.fcarcade.session;

/** Home seats do not promote; traditional cabinets retain their all-port reset. */
public final class ControllerDepartureInputs {
    private ControllerDepartureInputs() {}
    public static void clear(LockstepState state, boolean homeConsole, int departedController) {
        if (homeConsole) state.clearController(departedController);
        else state.clearInputs();
    }
}
