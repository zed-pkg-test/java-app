# frozen_string_literal: true

require "minitest/autorun"
require_relative "../lib/zed_pkg_client"

class ZedPkgClientTest < Minitest::Test
  def test_rejects_credentials_in_registry_url
    assert_raises(ArgumentError) { ZedPkg::Client.new(registry_url: "https://u:p@example.com") }
  end

  def test_accepts_https_registry_url
    assert_instance_of ZedPkg::Client, ZedPkg::Client.new(registry_url: "https://example.com/api/")
  end
end
