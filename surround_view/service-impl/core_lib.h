#ifndef WIRELESS_ANDROID_AUTOMOTIVE_CAML_SURROUND_VIEW_CORE_LIB_H_
#define WIRELESS_ANDROID_AUTOMOTIVE_CAML_SURROUND_VIEW_CORE_LIB_H_

#include <cstdint>
#include <vector>

namespace android_auto {
namespace surround_view {

// bounding box (bb)
// It is used to describe the car model bounding box in 3D.
// It assumes z = 0 and only x, y are used in the struct.
// Of course, it is compatible to the 2d version bounding box and may be used
// for other bounding box purpose (e.g., 2d bounding box in image).
struct BoundingBox {
  // (x,y) is bounding box's top left corner coordinate.
  float x;
  float y;

  // (width, height) is the size of the bounding box.
  float width;
  float height;

  BoundingBox() : x(0.0f), y(0.0f), width(0.0f), height(0.0f) {}

  BoundingBox(float x_, float y_, float width_, float height_)
      : x(x_), y(y_), width(width_), height(height_) {}

  BoundingBox(const BoundingBox& bb_)
      : x(bb_.x), y(bb_.y), width(bb_.width), height(bb_.height) {}

  // Checks if data is valid.
  bool IsValid() const { return width >= 0 && height >= 0; }

  bool operator==(const BoundingBox& rhs) const {
    return x == rhs.x && y == rhs.y && width == rhs.width &&
           height == rhs.height;
  }

  BoundingBox& operator=(const BoundingBox& rhs) {
    x = rhs.x;
    y = rhs.y;
    width = rhs.width;
    height = rhs.height;
    return *this;
  }
};

template <typename T>
struct Coordinate2dBase {
  // x coordinate.
  T x;

  // y coordinate.
  T y;

  Coordinate2dBase() : x(0), y(0) {}

  Coordinate2dBase(T x_, T y_) : x(x_), y(y_) {}

  bool operator==(const Coordinate2dBase& rhs) const {
    return x == rhs.x && y == rhs.y;
  }

  Coordinate2dBase& operator=(const Coordinate2dBase& rhs) {
    x = rhs.x;
    y = rhs.y;
    return *this;
  }
};

// integer type size.
typedef Coordinate2dBase<int> Coordinate2dInteger;

// float type size.
typedef Coordinate2dBase<float> Coordinate2dFloat;

struct Coordinate3dFloat {
  // x coordinate.
  float x;

  // y coordinate.
  float y;

  // z coordinate.
  float z;

  Coordinate3dFloat() : x(0), y(0), z(0) {}

  Coordinate3dFloat(float x_, float y_, float z_) : x(x_), y(y_), z(z_) {}

  bool operator==(const Coordinate3dFloat& rhs) const {
    return x == rhs.x && y == rhs.y;
  }

  Coordinate3dFloat& operator=(const Coordinate3dFloat& rhs) {
    x = rhs.x;
    y = rhs.y;
    return *this;
  }
};

//  pixel weight used for illumination assessment
struct PixelWeight {
  // x and y are the coordinates (absolute value) in image space.
  // pixel coordinate x in horizontal direction.
  float x;

  // pixel coordinate y in vertical direction.
  float y;

  // pixel weight, range in [0, 1].
  float weight;

  PixelWeight() : x(-1), y(-1), weight(0) {}

  PixelWeight(int x_, int y_, int weight_) : x(x_), y(y_), weight(weight_) {}

  bool operator==(const PixelWeight& rhs) const {
    return x == rhs.x && y == rhs.y && weight == rhs.weight;
  }

  PixelWeight& operator=(const PixelWeight& rhs) {
    x = rhs.x;
    y = rhs.y;
    weight = rhs.weight;
    return *this;
  }
};

// base size 2d type template.
template <typename T>
struct Size2dBase {
  // width of size.
  T width;

  // height of size.
  T height;

  Size2dBase() : width(0), height(0) {}

  Size2dBase(T width_, T height_) : width(width_), height(height_) {}

  bool IsValid() const { return width > 0 && height > 0; }

  bool operator==(const Size2dBase& rhs) const {
    return width == rhs.width && height == rhs.height;
  }

  Size2dBase& operator=(const Size2dBase& rhs) {
    width = rhs.width;
    height = rhs.height;
    return *this;
  }
};

// integer type size.
typedef Size2dBase<int> Size2dInteger;

// float type size.
typedef Size2dBase<float> Size2dFloat;

//  surround view 2d parameters
struct SurroundView2dParams {
  // surround view 2d image resolution (width, height).
  Size2dInteger resolution;

  // the physical size of surround view 2d area in surround view coordinate.
  // (surround view coordinate is defined as X rightward, Y forward and
  // the origin lies on the center of the (symmetric) bowl (ground).
  // When bowl is not used, surround view coordinate origin lies on the
  // center of car model bounding box.)
  // The unit should be consistent with camera extrinsics (translation).
  Size2dFloat physical_size;

  // the center of surround view 2d area in surround view coordinate
  // (consistent with extrinsics coordinate).
  Coordinate2dFloat physical_center;

  SurroundView2dParams()
      : resolution{0, 0},
        physical_size{0.0f, 0.0f},
        physical_center{0.0f, 0.0f} {}

  SurroundView2dParams(Size2dInteger resolution_, Size2dFloat physical_size_,
                       Coordinate2dFloat physical_center_)
      : resolution(resolution_),
        physical_size(physical_size_),
        physical_center(physical_center_) {}

  // Checks if data is valid.
  bool IsValid() const {
    return resolution.IsValid() && physical_size.IsValid();
  }

  bool operator==(const SurroundView2dParams& rhs) const {
    return resolution == rhs.resolution && physical_size == rhs.physical_size &&
           physical_center == rhs.physical_center;
  }

  SurroundView2dParams& operator=(const SurroundView2dParams& rhs) {
    resolution = rhs.resolution;
    physical_size = rhs.physical_size;
    physical_center = rhs.physical_center;
    return *this;
  }
};

//  surround view 3d parameters
struct SurroundView3dParams {
  // Bowl center is the origin of the surround view coordinate. If surround view
  // coordinate is different from the global one, a coordinate system
  // transformation function is required.

  // planar area radius.
  // Range in (0, +Inf).
  float plane_radius;

  // the number of divisions on the plane area of bowl, in the direction
  // of the radius.
  // Range in [1, +Inf).
  int plane_divisions;

  // bowl curve curve height.
  // Range in (0, +Inf).
  float curve_height;

  // the number of points on bowl curve curve along radius direction.
  // Range in [1, +Inf).
  int curve_divisions;

  // the number of points along circle (360 degrees)
  // Range in [1, +Inf).
  int angular_divisions;

  // the parabola coefficient of bowl curve curve.
  // The curve formula is z = a * (x^2 + y^2) for sqrt(x^2 + y^2) >
  // plane_radius; a is curve_coefficient.
  // Range in (0, +Inf).
  float curve_coefficient;

  // render output image size.
  Size2dInteger resolution;

  SurroundView3dParams()
      : plane_radius(0.0f),
        plane_divisions(0),
        curve_height(0.0f),
        curve_divisions(0),
        angular_divisions(0),
        curve_coefficient(0.0f),
        resolution(0, 0) {}

  SurroundView3dParams(float plane_radius_, int plane_divisions_,
                       float curve_height_, int curve_divisions_,
                       int angular_divisions_, float curve_coefficient_,
                       Size2dInteger resolution_)
      : plane_radius(plane_radius_),
        plane_divisions(plane_divisions_),
        curve_height(curve_height_),
        curve_divisions(curve_divisions_),
        angular_divisions(angular_divisions_),
        curve_coefficient(curve_coefficient_),
        resolution(resolution_) {}

  // Checks if data is valid.
  bool IsValid() const {
    return plane_radius > 0 && plane_divisions > 0 && curve_height > 0 &&
           angular_divisions > 0 && curve_coefficient > 0 &&
           curve_divisions > 0 && resolution.IsValid();
  }

  bool operator==(const SurroundView3dParams& rhs) const {
    return plane_radius == rhs.plane_radius &&
           plane_divisions == rhs.plane_divisions &&
           curve_height == rhs.curve_height &&
           curve_divisions == rhs.curve_divisions &&
           angular_divisions == rhs.angular_divisions &&
           curve_coefficient == rhs.curve_coefficient &&
           resolution == rhs.resolution;
  }

  SurroundView3dParams& operator=(const SurroundView3dParams& rhs) {
    plane_radius = rhs.plane_radius;
    plane_divisions = rhs.plane_divisions;
    curve_height = rhs.curve_height;
    curve_divisions = rhs.curve_divisions;
    angular_divisions = rhs.angular_divisions;
    curve_coefficient = rhs.curve_coefficient;
    resolution = rhs.resolution;
    return *this;
  }
};

// surround view camera parameters with native types only.
struct SurroundViewCameraParams {
  // All calibration data |intrinsics|, |rvec| and |tvec|
  // follow OpenCV format excepting using native arrays, refer:
  // https://docs.opencv.org/3.4.0/db/d58/group__calib3d__fisheye.html
  // camera intrinsics. It is the 1d array of camera matrix(3X3) with row first.
  float intrinsics[9];

  // lens distortion parameters.
  float distorion[4];

  // rotation vector.
  float rvec[3];

  // translation vector.
  float tvec[3];

  // camera image size (width, height).
  Size2dInteger size;

  // fisheye circular fov.
  float circular_fov;

  bool operator==(const SurroundViewCameraParams& rhs) const {
    return (0 == std::memcmp(intrinsics, rhs.intrinsics, 9 * sizeof(float))) &&
           (0 == std::memcmp(distorion, rhs.distorion, 4 * sizeof(float))) &&
           (0 == std::memcmp(rvec, rhs.rvec, 3 * sizeof(float))) &&
           (0 == std::memcmp(tvec, rhs.tvec, 3 * sizeof(float))) &&
           size == rhs.size && circular_fov == rhs.circular_fov;
  }

  SurroundViewCameraParams& operator=(const SurroundViewCameraParams& rhs) {
    std::memcpy(intrinsics, rhs.intrinsics, 9 * sizeof(float));
    std::memcpy(distorion, rhs.distorion, 4 * sizeof(float));
    std::memcpy(rvec, rhs.rvec, 3 * sizeof(float));
    std::memcpy(tvec, rhs.tvec, 3 * sizeof(float));
    size = rhs.size;
    circular_fov = rhs.circular_fov;
    return *this;
  }
};

// 3D vertex of an overlay object.
struct OverlayVertex {
  // Position in 3d coordinates in world space in order X,Y,Z.
  float pos[3];
  // RGBA values, A is used for transparency.
  uint8_t rgba[4];

  // normalized texture coordinates, in width and height direction. Range [0,
  // 1].
  float tex[2];

  // normalized vertex normal.
  float nor[3];

  bool operator==(const OverlayVertex& rhs) const {
    return (0 == std::memcmp(pos, rhs.pos, 3 * sizeof(float))) &&
           (0 == std::memcmp(rgba, rhs.rgba, 4 * sizeof(uint8_t))) &&
           (0 == std::memcmp(tex, rhs.tex, 2 * sizeof(float))) &&
           (0 == std::memcmp(nor, rhs.nor, 3 * sizeof(float)));
  }

  OverlayVertex& operator=(const OverlayVertex& rhs) {
    std::memcpy(pos, rhs.pos, 3 * sizeof(float));
    std::memcpy(rgba, rhs.rgba, 4 * sizeof(uint8_t));
    std::memcpy(tex, rhs.tex, 2 * sizeof(float));
    std::memcpy(nor, rhs.nor, 3 * sizeof(float));
    return *this;
  }
};

// Overlay is a list of vertices (may be a single or multiple objects in scene)
// coming from a single source or type of sensor.
struct Overlay {
  // Uniqiue Id identifying each overlay.
  uint16_t id;

  // List of overlay vertices. 3 consecutive vertices form a triangle.
  std::vector<OverlayVertex> vertices;

  // Constructor initializing all member.
  Overlay(uint16_t id_, const std::vector<OverlayVertex>& vertices_) {
    id = id_;
    vertices = vertices_;
  }

  // Default constructor.
  Overlay() {
    id = 0;
    vertices = std::vector<OverlayVertex>();
  }
};

enum Format {
  GRAY = 0,
  RGB = 1,
  RGBA = 2,
};

struct SurroundViewInputBufferPointers {
  void* gpu_data_pointer;
  void* cpu_data_pointer;
  Format format;
  int width;
  int height;
  SurroundViewInputBufferPointers()
      : gpu_data_pointer(nullptr),
        cpu_data_pointer(nullptr),
        width(0),
        height(0) {}
  SurroundViewInputBufferPointers(void* gpu_data_pointer_,
                                  void* cpu_data_pointer_, Format format_,
                                  int width_, int height_)
      : gpu_data_pointer(gpu_data_pointer_),
        cpu_data_pointer(cpu_data_pointer_),
        format(format_),
        width(width_),
        height(height_) {}
};

struct SurroundViewResultPointer {
  void* data_pointer;
  Format format;
  int width;
  int height;
  SurroundViewResultPointer() : data_pointer(nullptr), width(0), height(0) {}
  SurroundViewResultPointer(Format format_, int width_, int height_)
      : format(format_), width(width_), height(height_) {
    // default formate is gray.
    const int byte_per_pixel = format_ == RGB ? 3 : format_ == RGBA ? 4 : 1;
    data_pointer =
        static_cast<void*>(new char[width * height * byte_per_pixel]);
  }
  ~SurroundViewResultPointer() {
    if (data_pointer) {
      // delete[] static_cast<char*>(data_pointer);
      data_pointer = nullptr;
    }
  }
};

class SurroundView {
 public:
  virtual ~SurroundView() = default;

  // Sets SurroundView static data.
  // For each input, please refer to the definition.
  virtual bool SetStaticData(
      const std::vector<SurroundViewCameraParams>& cameras_params,
      const SurroundView2dParams& surround_view_2d_params,
      const SurroundView3dParams& surround_view_3d_params,
      const std::vector<float>& undistortion_focal_length_scales,
      const BoundingBox& car_model_bb) = 0;

  // Starts 2d pipeline. Returns false if error occurs.
  virtual bool Start2dPipeline() = 0;

  // Starts 3d pipeline. Returns false if error occurs.
  virtual bool Start3dPipeline() = 0;

  // Stops 2d pipleline. It releases resource owned by the pipeline.
  // Returns false if error occurs.
  virtual void Stop2dPipeline() = 0;

  // Stops 3d pipeline. It releases resource owned by the pipeline.
  virtual void Stop3dPipeline() = 0;

  // Updates 2d output resolution on-the-fly. Starts2dPipeline() must be called
  // before this can be called. For quality assurance, the resolution should not
  // be larger than the original one. This call is not thread safe and there is
  // no sync between Get2dSurroundView() and this call.
  virtual bool Update2dOutputResolution(const Size2dInteger& resolution) = 0;

  // Updates 3d output resolution on-the-fly. Starts3dPipeline() must be called
  // before this can be called. For quality assurance, the resolution should not
  // be larger than the original one. This call is not thread safe and there is
  // no sync between Get3dSurroundView() and this call.
  virtual bool Update3dOutputResolution(const Size2dInteger& resolution) = 0;

  // Projects camera's pixel location to surround view 2d image location.
  // camera_point is the pixel location in raw camera's space.
  // camera_index is the camera's index.
  // surround_view_2d_point is the surround view 2d image pixel location.
  virtual bool GetProjectionPointFromRawCameraToSurroundView2d(
      const Coordinate2dInteger& camera_point, int camera_index,
      Coordinate2dFloat* surround_view_2d_point) = 0;

  // Projects camera's pixel location to surround view 3d bowl coordinate.
  // camera_point is the pixel location in raw camera's space.
  // camera_index is the camera's index.
  // surround_view_3d_point is the surround view 3d vertex.
  virtual bool GetProjectionPointFromRawCameraToSurroundView3d(
      const Coordinate2dInteger& camera_point, int camera_index,
      Coordinate3dFloat* surround_view_3d_point) = 0;

  // Gets 2d surround view image.
  // It takes input_pointers as input, and output is result_pointer.
  // Please refer to the definition of SurroundViewInputBufferPointers and
  // SurroundViewResultPointer.
  virtual bool Get2dSurroundView(
      const std::vector<SurroundViewInputBufferPointers>& input_pointers,
      SurroundViewResultPointer* result_pointer) = 0;

  // Gets 3d surround view image.
  // It takes input_pointers and view_matrix as input, and output is
  // result_pointer. view_matrix is 4 x 4 matrix.
  // Please refer to the definition of
  // SurroundViewInputBufferPointers and
  // SurroundViewResultPointer.
  virtual bool Get3dSurroundView(
      const std::vector<SurroundViewInputBufferPointers>& input_pointers,
      const std::vector<std::vector<float>> view_matrix,
      SurroundViewResultPointer* result_pointer) = 0;

  // Sets 3d overlays.
  virtual bool Set3dOverlay(const std::vector<Overlay>& overlays) = 0;

  // for test only.
  // TODO(xxqian): remove thest two fns.
  virtual std::vector<SurroundViewInputBufferPointers> ReadImages(
      const char* filename0, const char* filename1, const char* filename2,
      const char* filename3) = 0;

  virtual void WriteImage(const SurroundViewResultPointer result_pointerer,
                          const char* filename) = 0;
};

SurroundView* Create();

}  // namespace surround_view
}  // namespace android_auto

#endif  // WIRELESS_ANDROID_AUTOMOTIVE_CAML_SURROUND_VIEW_CORE_LIB_H_
