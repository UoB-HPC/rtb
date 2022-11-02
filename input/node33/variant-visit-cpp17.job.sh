#!/bin/bash
# RTB gcc llvm dpcpp

# A basic std::variant + std::visit baseline, example codes taken from:
# https://en.cppreference.com/w/cpp/utility/variant
# https://en.cppreference.com/w/cpp/utility/variant/visit

_fixture() { :; }

_setup() { :; }

_test() {
  set -o xtrace
  "$RTB_CXX" $RTB_EXTRA_FLAGS -std=c++17 -O3 -march=native -xc++ - <<EOF
#include <variant>
#include <string>
#include <cassert>
#include <iostream>
#include <iomanip>
#include <type_traits>
#include <vector>

// the variant to visit
using var_t = std::variant<int, long, double, std::string>;

// helper constant for the visitor #3
template<class> inline constexpr bool always_false_v = false;

// helper type for the visitor #4
template<class... Ts> struct overloaded : Ts... { using Ts::operator()...; };
// explicit deduction guide (not needed as of C++20)
template<class... Ts> overloaded(Ts...) -> overloaded<Ts...>;

int main()
{
    std::variant<int, float> v, w;
    v = 42; // v contains int
    int i = std::get<int>(v);
    assert(42 == i); // succeeds
    w = std::get<int>(v);
    w = std::get<0>(v); // same effect as the previous line
    w = v; // same effect as the previous line

//  std::get<double>(v); // error: no double in [int, float]
//  std::get<3>(v);      // error: valid index values are 0 and 1

    try {
        std::get<float>(w); // w contains int, not float: will throw
    }
    catch (const std::bad_variant_access& ex) {
        std::cout << ex.what() << '\n';
    }

    using namespace std::literals;

    std::variant<std::string> x("abc");
    // converting constructors work when unambiguous
    x = "def"; // converting assignment also works when unambiguous

    std::variant<std::string, void const*> y("abc");
    // casts to void const * when passed a char const *
    assert(std::holds_alternative<void const*>(y)); // succeeds
    y = "xyz"s;
    assert(std::holds_alternative<std::string>(y)); // succeeds


    std::vector<var_t> vec = {10, 15l, 1.5, "hello"};
    for (auto& v: vec) {

        // 1. void visitor, only called for side-effects (here, for I/O)
        std::visit([](auto&& arg){std::cout << arg;}, v);

        // 2. value-returning visitor, demonstrates the idiom of returning another variant
        var_t w = std::visit([](auto&& arg) -> var_t {return arg + arg;}, v);

        // 3. type-matching visitor: a lambda that handles each type differently
        std::cout << ". After doubling, variant holds ";
        std::visit([](auto&& arg) {
            using T = std::decay_t<decltype(arg)>;
            if constexpr (std::is_same_v<T, int>)
                std::cout << "int with value " << arg << '\n';
            else if constexpr (std::is_same_v<T, long>)
                std::cout << "long with value " << arg << '\n';
            else if constexpr (std::is_same_v<T, double>)
                std::cout << "double with value " << arg << '\n';
            else if constexpr (std::is_same_v<T, std::string>)
                std::cout << "std::string with value " << std::quoted(arg) << '\n';
            else
                static_assert(always_false_v<T>, "non-exhaustive visitor!");
        }, w);
    }

    for (auto& v: vec) {
        // 4. another type-matching visitor: a class with 3 overloaded operator()'s
        // Note: The `(auto arg)` template operator() will bind to `int` and `long`
        //       in this case, but in its absence the `(double arg)` operator()
        //       *will also* bind to `int` and `long` because both are implicitly
        //       convertible to double. When using this form, care has to be taken
        //       that implicit conversions are handled correctly.
        std::visit(overloaded {
            [](auto arg) { std::cout << arg << ' '; },
            [](double arg) { std::cout << std::fixed << arg << ' '; },
            [](const std::string& arg) { std::cout << std::quoted(arg) << ' '; }
        }, v);
    }

}
EOF
}

_check() {
  ldd a.out
}
