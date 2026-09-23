#include <string>

extern "C" int juggFixtureCall() {
    return static_cast<int>(std::string("jugg-fixture").size());
}
