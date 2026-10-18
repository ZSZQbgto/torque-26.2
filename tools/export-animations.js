// 把 Blockbench 项目里的动画导出成 assets/torque/animations/player.json
//
// 用法：node tools/export-animations.js <项目文件> <输出 player.json>
//
// 输入既可以是 .bbmodel 本身，也可以是 Blockbench「导出 → 项目」得到的文本
// （那种文件外面还套一层 {"content": "..."}，这里会自动剥掉）。
//
// 换算规则只有一条：**不做任何换算**。
// Blockbench 的 Bedrock 动画坐标就是资源包里该有的坐标，rotation / position 原样写出；
// 坐标轴差异（Blockbench 的 Y 向上、Minecraft 模型的 Y 向下）在渲染端
// 由 AnimationBridge 统一处理一次，导出这一层不再翻任何符号。
// 以前这里对 rotation 的 x、y 取反、再给潜行开例外，两处取反叠在一起，
// 结果就是 json 里的数值和 Blockbench 里摆的姿势对不上——别再走那条路。
//
// 其余两件事只是让 json 干净一点，不改变采样结果：
//   1. 全 0 的通道直接丢掉（没写关键帧的骨骼本来就不会动）；
//   2. 整条通道数值都一样的只留第一帧（常量不需要三个关键帧）。

const fs = require('fs');

const [,, srcFile, outFile] = process.argv;
if (!srcFile || !outFile) {
  console.error('用法: node tools/export-animations.js <项目文件> <输出 player.json>');
  process.exit(1);
}

/** 读出 .bbmodel：兼容裸项目和 Blockbench 的文本导出包装 */
function readProject(file) {
  const raw = fs.readFileSync(file, 'utf8');
  const outer = JSON.parse(raw.slice(0, raw.lastIndexOf('}') + 1));
  return typeof outer.content === 'string' ? JSON.parse(outer.content) : outer;
}

/** 去掉浮点噪声，并把 -0 归成 0 */
function round(value) {
  const result = Math.round(Number(value) * 10000) / 10000;
  return Object.is(result, -0) ? 0 : result;
}

/** 通道名 → Blockbench 关键帧里的 channel 字段 */
const CHANNELS = ['rotation', 'position', 'scale'];

/** 一个关键帧上取单一插值模式的写法，和 Bedrock 的 lerp_mode 一一对应 */
function lerpMode(interpolation) {
  if (interpolation === 'catmullrom') return 'catmullrom';
  if (interpolation === 'step') return 'step';
  return 'linear';
}

/** 把一个 animator 的关键帧整理成 Bedrock 的通道对象 */
function parseAnimator(animator) {
  const raw = {};
  for (const keyframe of animator.keyframes || []) {
    const point = keyframe.data_points && keyframe.data_points[0];
    if (!point || !CHANNELS.includes(keyframe.channel)) continue;
    (raw[keyframe.channel] ||= {})[String(round(keyframe.time))] = {
      post: [round(point.x), round(point.y), round(point.z)],
      lerp_mode: lerpMode(keyframe.interpolation),
    };
  }

  const bone = {};
  for (const channel of CHANNELS) {
    const frames = raw[channel];
    if (!frames) continue;

    const times = Object.keys(frames).sort((a, b) => parseFloat(a) - parseFloat(b));
    const values = times.map((time) => frames[time].post);
    const isZero = values.every((value) => value.every((component) => component === 0));
    if (isZero) continue;

    // 整条通道恒定：一个关键帧就够，采样会一直停在这个值上
    const isConstant = values.every((value) => value.join() === values[0].join());
    const kept = isConstant ? [times[0]] : times;

    bone[channel] = {};
    for (const time of kept) {
      bone[channel][time] = frames[time];
    }
  }

  return bone;
}

const project = readProject(srcFile);
const output = { format_version: '1.8.0', animations: {} };

for (const animation of project.animations || []) {
  const bones = {};
  for (const animator of Object.values(animation.animators || {})) {
    if (!animator.name || !animator.keyframes || !animator.keyframes.length) continue;
    const bone = parseAnimator(animator);
    if (Object.keys(bone).length) bones[animator.name] = bone;
  }

  output.animations[animation.name] = {
    loop: animation.loop === 'loop' || animation.loop === true,
    animation_length: round(animation.length),
    bones,
  };
}

fs.writeFileSync(outFile, JSON.stringify(output, null, '\t') + '\n');

for (const [name, animation] of Object.entries(output.animations)) {
  console.log(`${name}: loop=${animation.loop}, length=${animation.animation_length}, bones=${Object.keys(animation.bones).length}`);
}
