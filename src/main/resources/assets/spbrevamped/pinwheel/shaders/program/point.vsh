#include veil:camera

layout (location = 0) in vec3 Position;
layout (location = 1) in vec3 LightPosition;
layout (location = 2) in vec3 Color;
layout (location = 3) in float Distance;
layout (location = 4) in float renderShadows;

out vec3 lightPos;
out vec3 lightColor;
out float radius;
out float shouldRenderShadows;

void main() {
    gl_Position = VeilCamera.ProjMat * (VeilCamera.ViewMat * vec4(LightPosition - VeilCamera.CameraPosition, 1.0) + vec4(Position * Distance, 0.0));
    lightPos = LightPosition;
    lightColor = Color;
    radius = Distance;
    shouldRenderShadows = renderShadows;
}