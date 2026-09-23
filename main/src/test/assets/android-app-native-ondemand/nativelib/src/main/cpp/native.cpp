#include <string>

extern "C" int juggOndemandFixtureCall() {
    return static_cast<int>(std::string("jugg-ondemand-fixture").size());
}
