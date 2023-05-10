// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "searchenvironment.h"
#include "search_environment_snapshot.h"
#include <vespa/vespalib/stllike/hash_map.hpp>
#include <vespa/searchsummary/config/config-juniperrc.h>

#include <vespa/log/log.h>
LOG_SETUP(".visitor.instance.searchenvironment");

using search::docsummary::JuniperProperties;
using vsm::VSMAdapter;

namespace streaming {

__thread SearchEnvironment::EnvMap * SearchEnvironment::_localEnvMap = nullptr;

SearchEnvironment::Env::Env(const config::ConfigUri& configUri, const Fast_NormalizeWordFolder& wf, FNET_Transport& transport, const vespalib::string& file_distributor_connection_spec)
    : _configId(configUri.getConfigId()),
      _configurer(std::make_unique<config::SimpleConfigRetriever>(createKeySet(configUri.getConfigId()), configUri.getContext()), this),
      _vsmAdapter(std::make_unique<VSMAdapter>(_configId, wf)),
      _rankManager(std::make_unique<RankManager>(_vsmAdapter.get())),
      _snapshot(),
      _lock(),
      _transport(transport),
      _file_distributor_connection_spec(file_distributor_connection_spec)
{
    _configurer.start();
}

config::ConfigKeySet
SearchEnvironment::Env::createKeySet(const vespalib::string & configId)
{
    config::ConfigKeySet set;
    set.add<vespa::config::search::vsm::VsmfieldsConfig,
            vespa::config::search::SummaryConfig,
            vespa::config::search::vsm::VsmsummaryConfig,
            vespa::config::search::summary::JuniperrcConfig,
            vespa::config::search::RankProfilesConfig>(configId);
    return set;
}

void
SearchEnvironment::Env::configure(const config::ConfigSnapshot & snapshot)
{
    vsm::VSMConfigSnapshot snap(_configId, snapshot);
    _vsmAdapter->configure(snap);
    _rankManager->configure(snap);
    auto se_snapshot = std::make_shared<const SearchEnvironmentSnapshot>(*_rankManager, *_vsmAdapter);
    std::lock_guard guard(_lock);
    std::swap(se_snapshot, _snapshot);
}

std::shared_ptr<const SearchEnvironmentSnapshot>
SearchEnvironment::Env::get_snapshot()
{
    std::lock_guard guard(_lock);
    return _snapshot;
}

SearchEnvironment::Env::~Env()
{
    _configurer.close();
}

SearchEnvironment::SearchEnvironment(const config::ConfigUri & configUri, FNET_Transport& transport, const vespalib::string& file_distributor_connection_spec)
    : VisitorEnvironment(),
      _envMap(),
      _configUri(configUri),
      _transport(transport),
      _file_distributor_connection_spec(file_distributor_connection_spec)
{
}


SearchEnvironment::~SearchEnvironment()
{
    std::lock_guard guard(_lock);
    _threadLocals.clear();
}

SearchEnvironment::Env &
SearchEnvironment::getEnv(const vespalib::string & searchCluster)
{
    config::ConfigUri searchClusterUri(_configUri.createWithNewId(searchCluster));
    if (_localEnvMap == nullptr) {
        EnvMapUP envMap = std::make_unique<EnvMap>();
        _localEnvMap = envMap.get();
        std::lock_guard guard(_lock);
        _threadLocals.emplace_back(std::move(envMap));
    }
    EnvMap::iterator localFound = _localEnvMap->find(searchCluster);
    if (localFound == _localEnvMap->end()) {
        std::lock_guard guard(_lock);
        EnvMap::iterator found = _envMap.find(searchCluster);
        if (found == _envMap.end()) {
            LOG(debug, "Init VSMAdapter with config id = '%s'", searchCluster.c_str());
            Env::SP env = std::make_shared<Env>(searchClusterUri, _wordFolder, _transport, _file_distributor_connection_spec);
            _envMap[searchCluster] = std::move(env);
            found = _envMap.find(searchCluster);
        }
        _localEnvMap->insert(*found);
        localFound = _localEnvMap->find(searchCluster);
    }
    return *localFound->second;
}

void
SearchEnvironment::clear_thread_local_env_map()
{
    _localEnvMap = nullptr;
}

std::shared_ptr<const SearchEnvironmentSnapshot>
SearchEnvironment::get_snapshot(const vespalib::string& search_cluster)
{
    return getEnv(search_cluster).get_snapshot();
}

}
