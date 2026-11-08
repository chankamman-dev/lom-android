// Batch front end for Unity's HLSLcc: DXBC vertex/fragment pairs in, GLES3 text and reflection out.
// usage: hlslcc-cli <flags-hex> <gl-lang-enum> <list-file>
// list lines: <vs.dxbc> <fs.dxbc> <out-prefix>
#include "hlslcc.h"
#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

static std::string slurp(const std::string& p)
{
    std::ifstream f(p, std::ios::binary);
    std::stringstream s;
    s << f.rdbuf();
    return s.str();
}

class Refl : public HLSLccReflection
{
public:
    std::ostringstream m;
    void OnDiagnostics(const std::string& e, int line, bool isError) override { m << (isError ? "E " : "W ") << line << " " << e << "\n"; }
    void OnInputBinding(const std::string& n, int i) override { m << "input " << n << " " << i << "\n"; }
    bool OnConstantBuffer(const std::string& n, size_t size, size_t count) override { m << "cb " << n << " " << size << " " << count << "\n"; return true; }
    bool OnConstant(const std::string& n, int bind, SHADER_VARIABLE_TYPE t, int r, int c, bool mat, int arr, bool cbBuf) override { m << "const " << n << " " << bind << " " << (int)t << " " << r << " " << c << " " << mat << " " << arr << "\n"; return true; }
    void OnConstantBufferBinding(const std::string& n, int i) override { m << "cbbind " << n << " " << i << "\n"; }
    void OnTextureBinding(const std::string& n, int bind, int sampler, bool ms, HLSLCC_TEX_DIMENSION dim, bool uav) override { m << "tex " << n << " " << bind << " " << sampler << " " << ms << " " << (int)dim << " " << uav << "\n"; }
    void OnBufferBinding(const std::string& n, int bind, bool uav) override { m << "buf " << n << " " << bind << " " << uav << "\n"; }
};

static bool one(const std::string& path, unsigned flags, GLLang lang, GLSLCrossDependencyData& dep, Refl& r, std::string& out)
{
    auto src = slurp(path);
    if (src.empty()) return false;
    GlExtensions ext{};
    HLSLccSamplerPrecisionInfo prec;
    GLSLShader sh;
    int ok = TranslateHLSLFromMem(src.data(), flags, lang, &ext, &dep, prec, r, &sh);
    if (!ok) return false;
    out = sh.sourceCode;
    return true;
}

int main(int argc, char** argv)
{
    unsigned flags = strtoul(argv[1], nullptr, 16);
    GLLang lang = (GLLang)atoi(argv[2]);
    std::ifstream list(argv[3]);
    std::string vs, fs, o;
    int good = 0, bad = 0;
    while (list >> vs >> fs >> o)
    {
        GLSLCrossDependencyData dep;
        Refl rv, rf;
        std::string a, b;
        bool ok = one(vs, flags, lang, dep, rv, a) && one(fs, flags, lang, dep, rf, b);
        std::ofstream(o + ".meta") << "[vs]\n" << rv.m.str() << "[fs]\n" << rf.m.str();
        if (!ok) { bad++; std::cerr << "FAIL " << o << "\n"; continue; }
        std::ofstream(o + ".glsl", std::ios::binary) << "#ifdef VERTEX\n" << a << "\n#endif\n#ifdef FRAGMENT\n" << b << "\n#endif\n";
        good++;
    }
    std::cout << good << " ok, " << bad << " failed\n";
    return bad ? 1 : 0;
}
