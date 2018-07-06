angular.module('slicebox.utils', ['ngSanitize'])

.factory('sbxMisc', function($q) {
    return {
        flatten: function(arrayOfArrays) {
            return [].concat.apply([], arrayOfArrays);
        },

        unique: function(array) {
            return array.filter(function (value, index, self) { return self.indexOf(value) === index; });
        },

        flattenPromises: function(arrayOfPromisesOfArrays) {
            return $q.all(arrayOfPromisesOfArrays).then(this.flatten);
        }
    };
})

.factory('sbxToast', function($mdToast) {
    return {
        showErrorMessage: function(errorMessage) {
            var toast = $mdToast.simple()
                .content(errorMessage)
                .action('Dismiss')
                .highlightAction(true)
                .hideDelay(30000)
                .theme('redTheme')
                .position("bottom right");
            $mdToast.show(toast);
        },

        showInfoMessage: function(infoMessage) {
            var toast = $mdToast.simple()
                .content(infoMessage)
                .parent(angular.element(document.getElementById("content")))
                .position("top right");
            $mdToast.show(toast);
        }
    };
})

.factory('sbxMetaData', function($http, sbxMisc) {
    return {
        imagesForSeries: function(series) {
            var self = this;
            var promises = series.map(function(singleSeries) {
                return $http.get('/api/metadata/images?startindex=0&count=100000000&seriesid=' + singleSeries.id).then(function (imagesData) {
                    return imagesData.data;
                });
            });
            return sbxMisc.flattenPromises(promises);
        },

        imagesForStudies: function(studies, sources, seriesTypes, seriesTags) {
            var self = this;
            var promises = studies.map(function(study) {
                return $http.get(self.urlWithAdvancedFiltering('/api/metadata/studies/' + study.id + '/images', sources, seriesTypes, seriesTags)).then(function (imagesData) {
                    return imagesData.data;
                });
            });
            return sbxMisc.flattenPromises(promises);
        },

        imagesForPatients: function(patients, sources, seriesTypes, seriesTags) {
            var self = this;
            var promises = patients.map(function(patient) {
                return $http.get(self.urlWithAdvancedFiltering('/api/metadata/patients/' + patient.id + '/images', sources, seriesTypes, seriesTags)).then(function (imagesData) {
                    return imagesData.data;
                });
            });
            return sbxMisc.flattenPromises(promises);
        },

        seriesForStudies: function(studies, sources, seriesTypes, seriesTags) {
            var self = this;
            var promises = studies.map(function(study) {
                return $http.get(self.urlWithAdvancedFiltering('/api/metadata/series?startindex=0&count=100000000&studyid=' + study.id, sources, seriesTypes, seriesTags)).then(function (seriesData) {
                    return seriesData.data;
                });
            });
            return sbxMisc.flattenPromises(promises);
        },

        studiesForPatients: function(patients, sources, seriesTypes, seriesTags) {
            var self = this;
            var promises = patients.map(function(patient) {
                return $http.get(self.urlWithAdvancedFiltering('/api/metadata/studies?startindex=0&count=1000000&patientid=' + patient.id, sources, seriesTypes, seriesTags)).then(function (studiesData) {
                    return studiesData.data;
                });
            });
            return sbxMisc.flattenPromises(promises);
        },

        seriesForPatients: function(patients, sources, seriesTypes, seriesTags) {
            var self = this;
            return self.studiesForPatients(patients, sources, seriesTypes, seriesTags).then(function (studies) {
                return self.seriesForStudies(studies, sources, seriesTypes, seriesTags);
            });
        },

        urlWithAdvancedFiltering: function(baseUrl, sources, seriesTypes, seriesTags) {
            var url = baseUrl;
            var queryPartEmpty = baseUrl.indexOf("?") < 0;
            var queryChar;
            if (sources && sources.length > 0) {
                var sourcesPart = sources.map(function (source) { return source.sourceType + ':' + source.sourceId; }).join();
                queryChar = queryPartEmpty ? '?' : '&';
                url = url + queryChar + 'sources=' + sourcesPart;
                queryPartEmpty = false;
            }
            if (seriesTypes && seriesTypes.length > 0) {
                var seriesTypesPart = seriesTypes.map(function (seriesType) { return seriesType.id; }).join();
                queryChar = queryPartEmpty ? '?' : '&';
                url = url + queryChar + 'seriestypes=' + seriesTypesPart;
                queryPartEmpty = false;
            }
            if (seriesTags && seriesTags.length > 0) {
                var seriesTagsPart = seriesTags.map(function (seriesTag) { return seriesTag.id; }).join();
                queryChar = queryPartEmpty ? '?' : '&';
                url = url + queryChar + 'seriestags=' + seriesTagsPart;
                queryPartEmpty = false;
            }
            return url;
        }
    };
})

.factory('openAddEntityModal', function($http, $mdDialog, sbxToast) {

    return function(modalContentName, controllerName, url, entityName, table) {
        var dialogPromise = $mdDialog.show({
            templateUrl: '/assets/partials/' + modalContentName,
            controller: controllerName
        });

        return dialogPromise.then(function (entity) {

            var addPromise = $http.post(url, entity).then(function(response) {
                sbxToast.showInfoMessage(entityName + " added");
                return response.data;
            }, function(error) {
                sbxToast.showErrorMessage(error.data);
                return error.data;
            });

            addPromise.finally(function() {
                table.reloadPage();
            });

            return addPromise;
        });
    };
})

.factory('openDeleteEntitiesModalFunction', function($mdDialog, $http, $q, openConfirmActionModal, sbxToast) {
    return function(url, entitiesText) {

        return function(entities) {
            var deleteConfirmationText = 'Permanently delete ' + entities.length + ' ' + entitiesText + '?';

            return openConfirmActionModal('Delete ' + entitiesText, deleteConfirmationText, 'Delete', function() {
                return deleteEntities(url, entities, entitiesText);
            });
        };
    };

    function deleteEntities(url, entities, entitiesText) {

        var removePromises = [];
        var removePromise;
        var deleteAllPromises;

        angular.forEach(entities, function(entity) {
            removePromise = $http.delete(url + entity.id);
            removePromises.push(removePromise);
        });

        deleteAllPromises = $q.all(removePromises);

        deleteAllPromises.then(function() {
            sbxToast.showInfoMessage(entities.length + " " + entitiesText + " deleted");
        }, function(response) {
            sbxToast.showErrorMessage(response.data);
        });

        return deleteAllPromises;
    }

})

.factory('openBulkDeleteEntitiesModalFunction', function($mdDialog, $http, $q, openConfirmActionModal, sbxToast) {
    return function(url, entitiesText) {

        return function(entities) {
            var deleteConfirmationText = 'Permanently delete ' + entities.length + ' ' + entitiesText + '?';

            return openConfirmActionModal('Delete ' + entitiesText, deleteConfirmationText, 'Delete', function() {
                return deleteEntities(url, entities, entitiesText);
            });
        };
    };

    function deleteEntities(url, entities, entitiesText) {
        return $http.post(url, entities).then(function() {
            sbxToast.showInfoMessage(entities.length + " " + entitiesText + " deleted");
        }, function(response) {
            sbxToast.showErrorMessage(response.data);
        });
    }

})

.factory('openConfirmActionModal', function($mdDialog) {

    return function(title, message, action, actionCallback) {

        return $mdDialog.show({
                templateUrl: '/assets/partials/confirmActionModalContent.html',
                controller: 'SbxConfirmActionModalController',
                locals: {
                        title: title,
                        message: message,
                        action: action,
                        actionCallback: actionCallback
                    }
            });
    };
})

.factory('openMessageModal', function($mdDialog) {

    return function(title, message) {

        return $mdDialog.show($mdDialog.alert({
            title: title,
            htmlContent: message,
            ok: 'Close'
        }));
    };
})

.controller('SbxConfirmActionModalController', function ($scope, $q, $mdDialog, title, message, action, actionCallback) {
    $scope.title = title;
    $scope.message = message;
    $scope.action = action;

    $scope.actionButtonClicked = function () {
        var actionPromise = actionCallback();

        actionPromise.finally(function() {
            $mdDialog.hide();
        });

        return actionPromise;
    };

    $scope.cancelButtonClicked = function () {
        $mdDialog.cancel();
    };
})

.factory('openTagSeriesModalFunction', function($http, $q, openTagSeriesModal, sbxMisc) {
    return function(urlPrefix) {
        return function(entries) {
            var entryIds = entries.map(function (entry) { return entry.id; });
            var imagesPromises = entryIds.map(function (entryId) { return $http.get(urlPrefix + entryId + "/images").then(function (imagesData) { return imagesData.data; }); });
            var imagesPromise = $q.all(imagesPromises).then(function (listOfImageLists) { return sbxMisc.flatten(listOfImageLists); });
            var seriesIdsPromise = imagesPromise.then(function (images) { return images.map(function (image) { return image.seriesId; }); });
            var uniqueSeriesIdsPromise = seriesIdsPromise.then(function (seriesIds) { return sbxMisc.unique(seriesIds); });

            return openTagSeriesModal(uniqueSeriesIdsPromise);
        };
    };
})

.factory('openUpdateModal', function($mdDialog, $http, sbxToast) {
    return function(url, entity, entityName, fieldName) {
        return $mdDialog.show({
            templateUrl: '/assets/partials/updateModalContent.html',
            controller: 'UpdateModalCtrl',
            locals: {
                entityName: entityName,
                fieldName: fieldName,
                updatedValue: entity[fieldName]
            }
        }).then(function (updatedValue) {
            entity[fieldName] = updatedValue;
            return $http.put(url, entity)
                .then(function () {
                    sbxToast.showInfoMessage("Entity updated");
                }, function (error) {
                    sbxToast.showErrorMessage(error);
                });
        });
    };
})

.controller('UpdateModalCtrl', function ($scope, $mdDialog, entityName, fieldName, updatedValue) {
    // Scope functions
    $scope.uiState = {
        entityName: entityName,
        fieldName: fieldName,
        updatedValue: updatedValue
    };

    $scope.applyButtonClicked = function() {
        $mdDialog.hide($scope.uiState.updatedValue);
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
})

.factory('openTagSeriesModal', function($mdDialog) {
    return function(seriesIdsPromise) {
        return $mdDialog.show({
            templateUrl: '/assets/partials/tagSeriesModalContent.html',
            controller: 'TagSeriesModalCtrl',
            locals: {
                seriesIds: seriesIdsPromise
            }
        });
    };
})

.controller('TagSeriesModalCtrl', function($scope, $mdDialog, $http, $q, sbxMisc, sbxToast, seriesIds) {
    $scope.uiState = {
        gatheringTags: true,
        hasChanges: false,
        seriesIds: seriesIds,
        originalSeriesTags: [],
        seriesTags: [],
        tagState: {
            searchText: ""
        }
    };

    var allTagsPromise = $http.get('/api/metadata/seriestags?count=100000&orderby=name').then(function (seriesTagsData) { return seriesTagsData.data; });

    var tagIdToTagPromise = allTagsPromise.then(function (allTags) {
        return allTags.reduce(function(map, tag) {
            map[tag.id] = tag;
            return map;
        }, {});
    });

    var idTagsPromise = $q.all(seriesIds.map(function (seriesId) {
        return $http.get('/api/metadata/series/' + seriesId + '/seriestags').then(function (tagsData) {
            return { seriesId: seriesId, tags: tagsData.data };
        });
    }));

    var existingTagIdsPromise = idTagsPromise.then(function (idTags) {
        return sbxMisc.unique(sbxMisc.flatten(idTags.map(function (idTag) {
            return idTag.tags.map(function (tag) {
                return tag.id;
            });
        })));
    });

    var tagIdToSeriesIdsPromise = existingTagIdsPromise.then(function (tagIds) {
        return idTagsPromise.then(function (idTags) {
            return tagIds.reduce(function (map, tagId) {
                var seriesIdsForTagId = idTags.filter(function (idTag) {
                    return idTag.tags.map(function (tag) { return tag.id; }).indexOf(tagId) >= 0;
                }).map(function (idTag) {
                    return idTag.seriesId;
                });
                map[tagId] = seriesIdsForTagId;
                return map;
            }, {});
        });
    });

    var tagInfoPromise = tagIdToTagPromise.then(function (tagIdToTag) {
        return tagIdToSeriesIdsPromise.then(function (tagIdToSeriesIds) {
            return { tagIdToTag: tagIdToTag, tagIdToSeriesIds: tagIdToSeriesIds };
        });
    });

    tagInfoPromise.then(function (tagInfo) {
        for (var tagId in tagInfo.tagIdToSeriesIds) {
            var tag = tagInfo.tagIdToTag[tagId];
            tag.nSeries = tagInfo.tagIdToSeriesIds[tagId].length;
            $scope.uiState.seriesTags.push(tag);
            $scope.uiState.originalSeriesTags.push(tag);
        }
        $scope.uiState.gatheringTags = false;
    });

    $scope.findSeriesTags = function(searchText) {
        var lcSearchText = angular.lowercase(searchText);
        var seriesTagNames = $scope.uiState.seriesTags.map(function (seriesTag) { return seriesTag.name; });
        return searchText ? allTagsPromise.then(function (seriesTags) {
            return seriesTags.filter(function (seriesTag) {
                var lcName = angular.lowercase(seriesTag.name);
                return lcName.indexOf(lcSearchText) === 0;
            }).filter(function (seriesTag) {
                return seriesTagNames.indexOf(seriesTag.name) < 0;
            });
        }) : [];
    };

    $scope.seriesTagAdded = function(tag) {
        var theTag = tag.name ? { id: tag.id, name: tag.name } : { id: -1, name: tag };
        var existingTagArray = $scope.uiState.seriesTags.filter(function (seriesTag) { return seriesTag.name === theTag.name; });
        if (existingTagArray.length === 0) {
            $scope.uiState.hasChanges = true;
            theTag.nSeries = $scope.uiState.seriesIds.length;
            $scope.uiState.seriesTags.push(theTag);
            return theTag;
        }
        return existingTagArray[0];
    };

    $scope.seriesTagRemoved = function(tag) {
        $scope.uiState.hasChanges = true;
        return tag;
    };

    $scope.okButtonClicked = function() {
        var added = getAddedTags($scope.uiState.seriesTags, $scope.uiState.originalSeriesTags);
        var removed = getRemovedTags($scope.uiState.seriesTags, $scope.uiState.originalSeriesTags);

        var addPromise = $q.all(sbxMisc.flatten(
            added.map(function (seriesTag) {
                return $scope.uiState.seriesIds.map(function (seriesId) {
                    return $http.post('/api/metadata/series/' + seriesId + '/seriestags', seriesTag);
                });
            })));
        var removePromise = $q.all(sbxMisc.flatten(
            removed.map(function (seriesTag) {
                return $scope.uiState.seriesIds.map(function (seriesId) {
                    return $http.delete('/api/metadata/series/' + seriesId + '/seriestags/' + seriesTag.id);
                });
            })));

        var promise = $q.all([addPromise, removePromise]);

        promise.then(function () {
            var addedMsg = added.length > 0 ? added.length + " tag(s) added to " + $scope.uiState.seriesIds.length + " series" : "";
            var removedMsg = removed.length > 0 ? removed.length + " tag(s) removed" : "";
            var msg = addedMsg + (addedMsg.length > 0 ? ", " : "") + removedMsg;
            if (msg.length > 0) {
                sbxToast.showInfoMessage(msg);
            }
        }, function(response) {
            sbxToast.showErrorMessage(response.data);
        });

        promise.finally(function() {
            $mdDialog.hide();
        });

        return promise;
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };

    function getAddedTags(tags, originalTags) {
        return tags.filter(function (tag) {
            for (var i = 0; i < originalTags.length; i++) {
                var originalTag = originalTags[i];
                if (originalTag.name === tag.name && originalTag.nSeries === tag.nSeries) {
                    return false;
                }
            }
            return true;
        });
    }

    function getRemovedTags(tags, originalTags) {
        return originalTags.filter(function (originalTag) {
            return tags.map(function (tag) { return tag.name; }).indexOf(originalTag.name) < 0;
        });
    }
})

.factory('userService', function ($http) {
    var service = {
        currentUserPromise: null,
        currentUser: null
    };

    service.updateCurrentUser = function() {
        service.currentUserPromise = $http.get('/api/users/current').success(function (user) {
            service.currentUser = user;
        }).error(function () {
            service.currentUser = null;
        });
        return service.currentUserPromise;
    };

    service.login = function (username, password) {
        return $http.post('/api/users/login', { user: username, pass: password });
    };

    service.logout = function() {
        return $http.post('/api/users/logout');
    };

    return service;
});