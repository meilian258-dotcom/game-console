"""Check actual held-model vertices through the MC 1.21.1 hand/layer transform chain.

Pure offline numerical QA, not a Minecraft screenshot or mod compatibility playtest.
Reads the final JSON, Java pose constants and local mapped Minecraft/NeoForge source jar.
"""
from __future__ import annotations
import argparse
import hashlib
import itertools
import json
import math
import re
import zipfile
from pathlib import Path
import numpy as np
from render_rocket_arcade_preview import collect_quads

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/piq_fc_arcade"
JAVA = ROOT / "src/main/java/cn/piq/fcarcade/client"


def rotation(axis, degrees):
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    m = np.eye(4)
    m[:3, :3] = (((1, 0, 0), (0, c, -s), (0, s, c)) if axis == "x" else
                 ((c, 0, s), (0, 1, 0), (-s, 0, c)) if axis == "y" else
                 ((c, -s, 0), (s, c, 0), (0, 0, 1)))
    return m


def translation(x, y, z):
    m = np.eye(4)
    m[:3, 3] = x, y, z
    return m


def scale(value):
    m = np.eye(4)
    m[0, 0], m[1, 1], m[2, 2] = value
    return m


def points(values, matrix):
    return (np.c_[np.asarray(values), np.ones(len(values))] @ matrix.T)[:, :3]


def display_matrix(display, left):
    xyz = list(display.get("rotation", [0, 0, 0]))
    offset = np.array(display.get("translation", [0, 0, 0]), dtype=float) / 16
    if left:
        offset[0] *= -1
        xyz[1] *= -1
        xyz[2] *= -1
    # Quaternion.rotationXYZ followed by model scale, after the mirrored translation.
    return translation(*offset) @ rotation("x", xyz[0]) @ rotation("y", xyz[1]) @ rotation("z", xyz[2]) @ scale(display.get("scale", [1, 1, 1]))


def java_number(source, name):
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*([-+]?\d*\.?\d+)\s*;", source)
    assert match, f"Missing numeric pose constant: {name}"
    return float(match[1])


def first_transform(right, two, swing, idle_y, two_z, mixed_z, equip=0, pitch=None):
    # Read active production pitch. Historical comparisons explicitly pass their
    # frozen -16 degree angle and must never silently inherit the current rig.
    if pitch is None:
        pitch = java_number((JAVA / 'ControllerPoseLayout.java').read_text(encoding='utf-8'), 'FIRST_PITCH')
    wave, side = math.sin(math.sqrt(swing) * math.pi), 1 if right else -1
    return (translation(0 if two else side*.34, idle_y-.45*equip+.018*wave,
                        (two_z if two else mixed_z)-.03*wave)
            @ rotation("x", pitch+4*wave) @ rotation("y", side*2*wave))


def projected_bounds(vertices, fov, aspect):
    assert (vertices[:, 2] < -.05).all(), "Near-plane crossing"
    denom = -vertices[:, 2] * math.tan(math.radians(fov / 2))
    ndc = vertices[:, :2] / np.c_[denom*aspect, denom]
    screen = np.c_[.5+ndc[:, 0]*.5, .5-ndc[:, 1]*.5]
    return ndc, [float(screen[:, 0].min()), float(screen[:, 1].min()),
                 float(screen[:, 0].max()), float(screen[:, 1].max())]


def first_arm_vertices(right, slim, sleeve, hand_size=None, historical=False):
    # Vanilla PlayerModel/HumanoidModel cubes. setupAnim resets the initially
    # 2.5-pixel slim pivot to y=2; neutralFirstArm cancels age=0 vanilla bob.
    side = 1 if right else -1
    min_x = (-2 if slim else -3) if right else -1
    max_x = min_x+(3 if slim else 4)
    inflate = .25 if sleeve else 0
    cube = np.array(list(itertools.product((min_x-inflate, max_x+inflate),
                                          (-2-inflate, 10+inflate), (-2-inflate, 2+inflate))))
    if hand_size is None:hand_size=java_number((JAVA/'ControllerPoseLayout.java').read_text(encoding='utf-8'),'DEFAULT_HAND_SIZE')
    grip=points([[-side*6/16,11/16,0]],rotation('z',side*24)@rotation('x',-38))[0]
    source=(JAVA/'ControllerPoseLayout.java').read_text(encoding='utf-8')
    pitch=-38 if historical else java_number(source,'FIRST_ARM_PITCH')
    roll=24 if historical else java_number(source,'FIRST_ARM_ROLL')
    next_grip=points([[-side*6/16,11/16,0]],rotation('z',side*roll)@rotation('x',pitch))[0]
    shift=np.array([side*.80,-.36,.38])+.82*(grip-hand_size*next_grip)
    local = (translation(*shift) @ rotation("z", side*roll)
             @ rotation("x", pitch) @ scale([.82*hand_size]*3)
             @ translation(-side*5/16, 2/16, 0) @ scale([1/16]*3))
    return points(cube, local)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/controller-pose-pipeline.json")
    args = parser.parse_args()
    source_jar = ROOT / "build/moddev/artifacts/neoforge-21.1.236-sources.jar"
    with zipfile.ZipFile(source_jar) as source:
        hand = source.read("net/minecraft/client/renderer/ItemInHandRenderer.java").decode()
        layer = source.read("net/minecraft/client/renderer/entity/layers/ItemInHandLayer.java").decode()
        item = source.read("net/minecraft/client/renderer/entity/ItemRenderer.java").decode()
        camera = source.read("net/minecraft/client/renderer/GameRenderer.java").decode()
        player = source.read("net/minecraft/client/model/PlayerModel.java").decode()
        humanoid = source.read("net/minecraft/client/model/HumanoidModel.java").decode()
        player_renderer = source.read("net/minecraft/client/renderer/entity/player/PlayerRenderer.java").decode()
    assert "applyForgeHandTransform" in hand and "-0.52F" in hand and "-0.72F" in hand
    assert "rotationDegrees(-90.0F)" in layer and "rotationDegrees(180.0F)" in layer
    assert "0.125F, -0.625F" in layer and "translateToHand" in layer
    assert item.index("handleCameraTransforms") < item.index("translate(-0.5F, -0.5F, -0.5F)") < item.index("getCustomRenderer().renderByItem")
    assert "double d0 = 70.0;" in camera and "getFov(p_109122_, p_109123_, false)" in camera
    assert "float f = 0.5F" in player and "modelpart.x += f;" in player and "modelpart.x -= f;" in player
    assert "this.setupAttackAnimation" in humanoid and "AnimationUtils.bobModelPart" in humanoid
    assert "this.leftArm.y = 2.0F;" in humanoid and "this.rightArm.y = 2.0F;" in humanoid
    assert "this.leftSleeve.copyFrom(this.leftArm)" in player and "this.rightSleeve.copyFrom(this.rightArm)" in player
    assert "p_117780_.xRot = 0.0F" in player_renderer and "p_117781_.xRot = 0.0F" in player_renderer
    for snippet in ("addBox(-2.0F, -2.0F, -2.0F, 3.0F, 12.0F, 4.0F",
                    "addBox(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F", "p_170826_.extend(0.25F)"):
        assert snippet in player, snippet
    java = (JAVA / "ControllerPoseLayout.java").read_text(encoding="utf-8")
    # Couple the reference transform to the implementation: fail if its reviewed constants/order change.
    for snippet in ("THIRD_ARM_PITCH = -Math.PI / 3", "THIRD_ARM_INWARD_ROLL = Math.toRadians(38)", "side * .34",
                    "FIRST_IDLE_Y - .45 * equip + .018 * wave", "(twoHands ? FIRST_TWO_HAND_Z : FIRST_MIXED_Z) - .03 * wave",
                    "FIRST_ARM_PITCH,side*FIRST_ARM_ROLL,FIRST_ARM_SCALE*size)"):
        assert snippet in java, snippet
    idle_y = java_number(java, "FIRST_IDLE_Y")
    two_z = java_number(java, "FIRST_TWO_HAND_Z")
    mixed_z = java_number(java, "FIRST_MIXED_Z")
    pitch = java_number(java, "FIRST_PITCH")
    assert pitch == -60, 'Reviewed alpha10 upward-facing slanted grip'
    assert java_number(java, "FIRST_ARM_SCALE") == .82
    pose_source = (JAVA / "ControllerPose.java").read_text(encoding="utf-8")
    assert "applyFirst(poses, ControllerPoseLayout.first(arm == HumanoidArm.RIGHT, two, equip, swing))" in pose_source
    assert "applyFirst(poses, ControllerPoseLayout.first(arm == HumanoidArm.RIGHT, true, event.getEquipProgress(), event.getSwingProgress()))" in pose_source
    assert "renderer.renderRightHand(" in pose_source and "renderer.renderLeftHand(" in pose_source
    assert "else return; // Never take the arm" in pose_source
    renderer = (JAVA / "HomeHardwareRenderer.java").read_text(encoding="utf-8")
    assert "HomeHardwareRenderLayout.heldControllerYaw(port)" in renderer
    layout = (JAVA / "HomeHardwareRenderLayout.java").read_text(encoding="utf-8")
    assert "return port == 0 ? -90f : 90f;" in layout
    item_path = ASSETS / "models/item/fc_controller.json"
    displays = json.loads(item_path.read_bytes())["display"]
    for name in ("thirdperson_righthand", "thirdperson_lefthand"):
        assert displays[name]["scale"] == [.6]*3, "Preserve the original compact controller size"
    for name in ("firstperson_righthand", "firstperson_lefthand"):
        assert displays[name]["scale"] == [.85]*3, "Only the common rig translation changes"
        assert displays[name]["translation"] == [0, 0, 0] and displays[name]["rotation"] == [0, 0, 0]
    model_reports = []
    scenarios = []
    idle_comparisons = []
    conservative = np.array(list(itertools.product((6.85, 9.15), (5.15, 10.85), (1.55, 14.45))))
    for port in (0, 1):
        path = ASSETS / f"models/block/home_controller_p{port+1}_held.json"
        raw = path.read_bytes()
        model = json.loads(raw)
        quads = collect_quads(model)
        all_vertices = np.concatenate([q.vertices for q in quads])
        assert (all_vertices.min(axis=0) >= conservative.min(axis=0)-1e-6).all()
        assert (all_vertices.max(axis=0) <= conservative.max(axis=0)+1e-6).all()
        canonical = rotation("y", -90 if port == 0 else 90) @ scale([1/16]*3) @ translation(-8, -8, -8)
        normalized = points(all_vertices, canonical)
        names = {e["name"]: e for e in model["elements"]}
        prefix = "一号" if port == 0 else "二号"
        controls = [names[prefix + name] for name in ("十字键横", "A黑色圆钮·横芯")]
        controls = np.array([(np.array(e["from"]) + np.array(e["to"])) / 2 for e in controls])
        model_reports.append({"port": port, "elements": len(model["elements"]), "quads": len(quads),
                              "sha256": hashlib.sha256(raw).hexdigest().upper(),
                              "canonical_bounds": [normalized.min(axis=0).tolist(), normalized.max(axis=0).tolist()]})
        for right in (True, False):
            first_item = display_matrix(displays["firstperson_righthand" if right else "firstperson_lefthand"], not right)
            for two in (True, False):
                for swing in (0, .05, .125, .25, .5, .75, 1):
                    hook = first_transform(right, two, swing, idle_y, two_z, mixed_z)
                    matrix = hook @ first_item @ canonical
                    vertices = points(all_vertices, matrix)
                    box = points(conservative, matrix)
                    button_pixels = points(controls, matrix)
                    assert button_pixels[0, 0] / -button_pixels[0, 2] < button_pixels[1, 0] / -button_pixels[1, 2], "D-pad/A reversed"
                    for fov in (60, 70):
                        for aspect in (4/3, 16/9):
                            ndc, screen_box = projected_bounds(vertices, fov, aspect)
                            box_ndc, conservative_screen = projected_bounds(box, fov, aspect)
                            assert (abs(ndc) < 1).all(), f"Clipped actual controller: {port,right,two,swing,fov,aspect}"
                            assert (abs(box_ndc) < 1).all(), f"Clipped conservative box: {port,right,two,swing,fov,aspect}"
                            assert conservative_screen[3] < .98, "Less than 2% bottom clearance, including water FOV"
                            assert conservative_screen[1] > .69, "Controller returns to the middle of the screen"
                            scenarios.append({"port": port, "right": right, "two_hands": two, "swing": swing,
                                              "fov": fov, "aspect": aspect, "max_abs_ndc": abs(ndc).max(axis=0).tolist(),
                                              "actual_screen_ltrb": screen_box, "conservative_screen_ltrb": conservative_screen})
                            if swing == 0:
                                old = points(all_vertices, first_transform(right, two, 0, -.22, -.90, -1.02, pitch=-16) @ first_item @ canonical)
                                _, old_screen = projected_bounds(old, fov, aspect)
                                assert screen_box[1]-old_screen[1] > .13, "The complete controller must visibly move down"
                                if two and fov == 70:
                                    assert .72 <= conservative_screen[1] <= .74
                                    assert .88 <= conservative_screen[3] <= .90
                                    assert .15 <= conservative_screen[3]-conservative_screen[1] <= .17
                                idle_comparisons.append({"port": port, "right": right, "two_hands": two, "fov": fov, "aspect": aspect,
                                                         "alpha4_screen_ltrb": old_screen, "current_screen_ltrb": screen_box})
    # Both hands use exactly the same first() camera transform as the item.
    # Shoulder/forearm tails may extend below the viewport naturally; never call
    # that a clipped controller. Require their upper visible edges to move down.
    arm_reports = []
    for holding_right, arm_right, slim, sleeve, swing, fov, aspect in itertools.product(
            (True, False), (True, False), (True, False), (True, False),
            (0, .05, .125, .25, .5, .75, 1), (60, 70), (4/3, 16/9)):
        local = first_arm_vertices(arm_right, slim, sleeve)
        transformed = points(local, first_transform(holding_right, True, swing, idle_y, two_z, mixed_z))
        _, bounds = projected_bounds(transformed, fov, aspect)
        # Alpha10 rotates the same 18%-enlarged arms and fixed local palm anchors
        # with the controller. Their worst swing edge stays in the bottom 30%.
        assert bounds[1] >= .705, f"Enlarged arm/sleeve re-enters the central screen: {holding_right,arm_right,slim,sleeve,swing,fov,aspect}"
        report_arm = {"holding_right": holding_right, "arm_right": arm_right, "slim": slim, "sleeve": sleeve,
                      "swing": swing, "fov": fov, "aspect": aspect, "screen_ltrb": bounds}
        if swing == 0:
            historical_local=first_arm_vertices(arm_right,slim,sleeve,1,historical=True)
            _, before = projected_bounds(points(historical_local, first_transform(holding_right, True, 0, -.22, -.90, -1.02, pitch=-16)), fov, aspect)
            assert bounds[1]-before[1] > .10
            report_arm["alpha4_screen_ltrb"] = before
        arm_reports.append(report_arm)
    # Full third-person chain, including the default/slim translateToHand difference.
    third_reports = []
    for right in (True, False):
        for slim in (True, False):
            item_transform = display_matrix(displays["thirdperson_righthand" if right else "thirdperson_lefthand"], not right)
            side = 1 if right else -1
            for tick in (0, 20, 40, 60, 80):
                # setupAnim's normal idle bob follows the custom ArmPose; no attack or swimming is synthesized here.
                bob_z = math.cos(tick*.09)*.05+.05
                bob_x = math.sin(tick*.067)*.05
                arm = translation(-side*(4.5 if slim else 5)/16, 2/16, 0) @ rotation("z", math.degrees(side*(-math.radians(38)-.1+bob_z))) @ rotation("x", -60+math.degrees(side*bob_x))
                chain = arm @ rotation("x", -90) @ rotation("y", 180) @ translation(side/16, .125, -.625) @ item_transform
                center = points([[0, 0, 0]], chain)[0]
                normal = chain[:3, :3] @ [0, 0, 1]
                eyes = np.array([0, -6/16, 0])-center
                cosine = float(normal @ eyes / np.linalg.norm(normal) / np.linalg.norm(eyes))
                # Idle bob plus slim's built-in .5 model-pixel translateToHand offset: under 1.12 pixels.
                assert abs(center[0]) < .07 and .27 < center[1] < .38 and -.64 < center[2] < -.55
                assert cosine > .985
                third_reports.append({"right": right, "slim": slim, "tick": tick,
                                      "center_model_space": center.tolist(), "face_to_eyes_cosine": cosine})
    report = {"ok": True, "method": "Actual held vertices through ItemInHandRenderer hook + ItemTransform + ItemRenderer center/BEWLR yaw; actual third-person layer chain",
              "item_display_sha256": hashlib.sha256(item_path.read_bytes()).hexdigest().upper(),
              "pose_source_sha256": hashlib.sha256((JAVA / "ControllerPose.java").read_bytes()).hexdigest().upper(),
              "constants_source_sha256": hashlib.sha256((JAVA / "ControllerPoseLayout.java").read_bytes()).hexdigest().upper(),
              "source_constants": {"third_arm_pitch_degrees": -60, "third_arm_effective_idle_inward_roll_degrees": 38,
                                   "third_item_scale": .6, "first_item_scale": .85, "first_pitch_degrees": pitch,
                                   "first_solo_idle_center": [0,idle_y,two_z], "first_mixed_idle_center_abs_x_y_z": [.34,idle_y,mixed_z]},
              "models": model_reports, "first_person_scenarios": len(scenarios), "first_person": scenarios,
              "conservative_model_bounds": [conservative.min(axis=0).tolist(), conservative.max(axis=0).tolist()],
              "idle_before_after": idle_comparisons,
              "first_person_arm_scenarios": len(arm_reports), "first_person_arms": arm_reports,
              "third_person_scenarios": len(third_reports), "third_person": third_reports,
              "source_contracts": {name: hashlib.sha256(value.encode()).hexdigest() for name, value in
                                   {"ItemInHandRenderer":hand,"ItemInHandLayer":layer,"ItemRenderer":item,"GameRenderer":camera,
                                    "PlayerModel":player,"PlayerRenderer":player_renderer,"HumanoidModel":humanoid}.items()},
              "limits": ["Offline numerical QA, not an actual Minecraft playtest or screenshot",
                         "Vanilla 70 degree hand camera and approximately 60 degree water case; another mod may override hand FOV",
                         "Equipped state checked; equip animation intentionally lowers the object in/out of view",
                         "Default/slim arm and .25-pixel inflated sleeve boxes follow actual setupAnim y=2 pivots; forearm tails may remain below the viewport",
                         "Mixed held objects retain their vanilla arm/event path; this tool checks only the FC item for mixed holding",
                         "Third person checks normal idle bob; swimming/flying/another active-use item deliberately use vanilla pose",
                         "No source assets changed; no global keys or third-party internals changed"]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(f"PASS: {len(scenarios)} actual-mesh + conservative-box perspective scenarios, {len(arm_reports)} arm/sleeve projections, {len(third_reports)} third-person default/slim idle poses, P1/P2 D-pad left of A")
    print(str(args.output))


if __name__ == "__main__":
    main()
