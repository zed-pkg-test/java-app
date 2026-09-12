Gem::Specification.new do |spec|
  spec.name = "zed_pkg_client"
  spec.version = "0.1.0"
  spec.summary = "Ruby client for the zed-pkg registry"
  spec.authors = ["zed-pkg contributors"]
  spec.license = "MIT"
  spec.required_ruby_version = ">= 3.1"
  spec.files = Dir["lib/**/*.rb", "README.md"]
  spec.require_paths = ["lib"]
end
