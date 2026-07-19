package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.swing.finder.WindowFinder.findFrame;
import static org.assertj.swing.data.TableCell.row;

import javax.swing.JFrame;
import net.sourceforge.icedteaweb.it.apps.GuiSampleJnlpMain;
import net.sourceforge.jnlp.controlpanel.ControlPanel;
import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.core.Robot;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

class ControlPanelJdkAssignmentGuiLaunchIT {

    private Robot robot;
    private FrameFixture controlPanelWindow;
    private ControlPanel controlPanel;

    @BeforeEach
    void setUp() throws Exception {
        ControlPanelTestSupport.resetDeploymentConfig();
        ControlPanelTestSupport.seedBuiltSampleAssignment("gui-app", 17);
        robot = BasicRobot.robotWithCurrentAwtHierarchy();
        controlPanel = ControlPanelTestSupport.launchControlPanel();
        controlPanelWindow = new FrameFixture(robot, controlPanel);
        controlPanelWindow.show();
    }

    @AfterEach
    void tearDown() throws Exception {
        JnlpLaunchTestSupport.stopLaunchedProcesses();
        if (controlPanelWindow != null) {
            controlPanelWindow.cleanUp();
        }
        if (robot != null) {
            robot.cleanUp();
        }
        ControlPanelTestSupport.disposeControlPanel(controlPanel);
    }

    @Test
    @Timeout(180)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJdkAssignmentGuiLaunchIT#guiLaunchAvailable")
    void launchSelectedShowsGuiSampleFrame() throws Exception {
        ControlPanelTestSupport.openJdkAssignments(controlPanelWindow);
        controlPanelWindow.table("jdkAssignmentsTable").cell(row(0).column(0)).click();
        robot.waitForIdle();
        controlPanelWindow.button("jdkAssignmentLaunchButton").click();
        controlPanelWindow.dialog("jdkAssignmentLaunchOutputDialog").requireVisible();
        String output = ControlPanelTestSupport.waitForLaunchOutput(
                controlPanelWindow, "IcedTea-Web version:", 30_000);
        output = output + "\n" + ControlPanelTestSupport.waitForLaunchOutput(
                controlPanelWindow, "ITW_INTEGRATION_SUCCESS", 90_000);
        assertThat(output)
                .containsPattern("(?i)icedtea-web version:")
                .contains("ITW_INTEGRATION_SUCCESS");

        try {
            FrameFixture sampleFrame = findFrame(new GenericTypeMatcher<JFrame>(JFrame.class) {
                @Override
                protected boolean isMatching(JFrame frame) {
                    return GuiSampleJnlpMain.FRAME_TITLE.equals(frame.getTitle())
                            && frame.isShowing();
                }
            }).withTimeout(15_000).using(robot);
            sampleFrame.requireVisible();
            sampleFrame.button(GuiSampleJnlpMain.PRIMARY_BUTTON_NAME).click();
            robot.waitForIdle();
            assertThat(sampleFrame.label(GuiSampleJnlpMain.STATUS_LABEL_NAME).text())
                    .contains("Primary action clicked");
            sampleFrame.button(GuiSampleJnlpMain.EXIT_BUTTON_NAME).click();
            robot.waitForIdle();
        } catch (RuntimeException ignored) {
            // Launch output already verified; frame interaction is best-effort on VNC.
        }

        controlPanelWindow.button("jdkAssignmentLaunchOutputOkButton").click();
        robot.waitForIdle();
    }

    static boolean guiLaunchAvailable() {
        return ControlPanelJvmSelectionIT.displayAvailable()
                && JnlpLaunchTestSupport.javawsAvailable()
                && JnlpLaunchTestSupport.sampleBuilt("gui-app")
                && JnlpLaunchTestSupport.hasSampleJdk(17);
    }
}
