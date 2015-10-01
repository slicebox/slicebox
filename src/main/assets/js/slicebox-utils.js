angular.module('slicebox.utils', [])

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
            var toast = {
                template: '<md-toast>' + infoMessage + '</md-toast>',
                position: 'top right'
            };
            var parent = angular.element(document.getElementById("content"));
            if (parent) {
                toast.parent = parent;
            }
            $mdToast.show(toast);
        }
    };
})

.factory('sbxMetaData', function($http, sbxMisc) {
    return {
        imagesForSeries: function(series, sources, seriesTypes, seriesTags) {
            var self = this;
            var promises = series.map(function(singleSeries) {
                return $http.get(self.urlWithAdvancedFiltering('/api/metadata/images?startindex=0&count=1000000&seriesid=' + singleSeries.id, sources, seriesTypes, seriesTags)).then(function (imagesData) {
                    return imagesData.data;
                });
            });
            return sbxMisc.flattenPromises(promises);
        },

        imagesForStudies: function(studies, sources, seriesTypes, seriesTags) {
            var self = this;
            var promises = studies.map(function(study) {
                return $http.get(self.urlWithAdvancedFiltering('/api/metadata/series?startindex=0&count=1000000&studyid=' + study.id, sources, seriesTypes, seriesTags)).then(function (seriesData) {
                    return self.imagesForSeries(seriesData.data, sources, seriesTypes, seriesTags);
                });
            });
            return sbxMisc.flattenPromises(promises);
        },

        imagesForPatients: function(patients, sources, seriesTypes, seriesTags) {
            var self = this;
            var promises = patients.map(function(patient) {
                return $http.get(self.urlWithAdvancedFiltering('/api/metadata/studies?startindex=0&count=1000000&patientid=' + patient.id, sources, seriesTypes, seriesTags)).then(function (studiesData) {
                    return self.imagesForStudies(studiesData.data, sources, seriesTypes, seriesTags);
                });
            });
            return sbxMisc.flattenPromises(promises);
        },

        seriesForStudies: function(studies, sources, seriesTypes, seriesTags) {
            var self = this;
            var promises = studies.map(function(study) {
                return $http.get(self.urlWithAdvancedFiltering('/api/metadata/series?startindex=0&count=1000000&studyid=' + study.id, sources, seriesTypes, seriesTags)).then(function (seriesData) {
                    return seriesData.data;
                });
            });
            return sbxMisc.flattenPromises(promises);            
        },

        seriesForPatients: function(patients, sources, seriesTypes, seriesTags) {
            var self = this;
            var promises = patients.map(function(patient) {
                return $http.get(self.urlWithAdvancedFiltering('/api/metadata/studies?startindex=0&count=1000000&patientid=' + patient.id, sources, seriesTypes, seriesTags)).then(function (studiesData) {
                    return self.seriesForStudies(studiesData.data, sources, seriesTypes, seriesTags);
                });
            });
            return sbxMisc.flattenPromises(promises);
        },

        urlWithAdvancedFiltering: function(baseUrl, sources, seriesTypes, seriesTags) {
            var url = baseUrl;
            if (sources && sources.length > 0) {
                var sourcesPart = sources.map(function (source) { return source.sourceType + ':' + source.sourceId; }).join();
                url = url + '&sources=' + sourcesPart;
            }
            if (seriesTypes && seriesTypes.length > 0) {
                var seriesTypesPart = seriesTypes.map(function (seriesType) { return seriesType.id; }).join();
                url = url + '&seriestypes=' + seriesTypesPart;
            }
            if (seriesTags && seriesTags.length > 0) {
                var seriesTagsPart = seriesTags.map(function (seriesTag) { return seriesTag.id; }).join();
                url = url + '&seriestags=' + seriesTagsPart;
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

        dialogPromise.then(function (entity) {

            var addPromise = $http.post(url, entity);
            addPromise.error(function(data) {
                sbxToast.showErrorMessage(data);
            });

            addPromise.success(function() {
                sbxToast.showInfoMessage(entityName + " added");                
            });

            addPromise.finally(function() {
                table.reloadPage();
            });
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
            content: message,
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
        seriesIds: seriesIds,
        seriesTags: [],
        tagState: {
            searchText: ""
        }
    };

    var seriesTagsPromise = $http.get('/api/metadata/seriestags').then(function (seriesTagsData) { return seriesTagsData.data; });

    $scope.okButtonClicked = function() {
        var promise = 
            $q.all(
                sbxMisc.flatten(
                    $scope.uiState.seriesTags.map(function (seriesTag) {
                        return $scope.uiState.seriesIds.map(function (seriesId) {
                            return $http.post('/api/metadata/series/' + seriesId + '/seriestags', seriesTag);
                        });
                    })
                )
            );

        promise.then(function() {
            sbxToast.showInfoMessage(seriesIds.length + " series tagged.");
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

    $scope.findSeriesTags = function(searchText) {
        var lcSearchText = angular.lowercase(searchText);
        var selectedTagNames = $scope.uiState.seriesTags.map(function (seriesTag) { return seriesTag.name; });
        return searchText ? seriesTagsPromise.then(function (seriesTags) { 
            return seriesTags.filter(function (seriesTag) {
                var lcName = angular.lowercase(seriesTag.name);
                return lcName.indexOf(lcSearchText) === 0;
            }).filter(function (seriesTag) {
                return selectedTagNames.indexOf(seriesTag.name) < 0;
            });
        }) : [];
    };

    $scope.seriesTagAdded = function(tag) {
        var theTag = tag.name ? tag : { id: -1, name: tag };
        if ($scope.uiState.seriesTags.filter(function (seriesTag) { return seriesTag.name === tag.name; }).length === 0) {
            $scope.uiState.seriesTags.push(theTag);
        }
        return theTag;
    };

})

.factory('authenticationService', function (base64, $http, $cookieStore, $rootScope, $timeout) {
        var service = {};
 
        service.login = function (username, password, callback) {
 
            $http.post('/login', { user: username, pass: password })
                .success(function (response) {
                    callback(response);
                });
 
        };
  
        service.setCredentials = function (username, password, role) {
            var authdata = base64.encode(username + ':' + password);
  
            $rootScope.globals = {
                currentUser: {
                    username: username,
                    role: role,
                    authdata: authdata
                }
            };
  
            $http.defaults.headers.common['Authorization'] = 'Basic ' + authdata; // jshint ignore:line
            $cookieStore.put('globals', $rootScope.globals);
        };
  
        service.clearCredentials = function () {
            $rootScope.globals = {};
            $cookieStore.remove('globals');
            $http.defaults.headers.common.Authorization = 'Basic ';
        };

        service.userSignedIn = function() {
            return $rootScope.globals.currentUser;
        };
  
        return service;
    })
  
.factory('base64', function () {
    /* jshint ignore:start */
  
    var keyStr = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
  
    return {
        encode: function (input) {
            var output = "";
            var chr1, chr2, chr3 = "";
            var enc1, enc2, enc3, enc4 = "";
            var i = 0;
  
            do {
                chr1 = input.charCodeAt(i++);
                chr2 = input.charCodeAt(i++);
                chr3 = input.charCodeAt(i++);
  
                enc1 = chr1 >> 2;
                enc2 = ((chr1 & 3) << 4) | (chr2 >> 4);
                enc3 = ((chr2 & 15) << 2) | (chr3 >> 6);
                enc4 = chr3 & 63;
  
                if (isNaN(chr2)) {
                    enc3 = enc4 = 64;
                } else if (isNaN(chr3)) {
                    enc4 = 64;
                }
  
                output = output +
                    keyStr.charAt(enc1) +
                    keyStr.charAt(enc2) +
                    keyStr.charAt(enc3) +
                    keyStr.charAt(enc4);
                chr1 = chr2 = chr3 = "";
                enc1 = enc2 = enc3 = enc4 = "";
            } while (i < input.length);
  
            return output;
        },
  
        decode: function (input) {
            var output = "";
            var chr1, chr2, chr3 = "";
            var enc1, enc2, enc3, enc4 = "";
            var i = 0;
  
            // remove all characters that are not A-Z, a-z, 0-9, +, /, or =
            var base64test = /[^A-Za-z0-9\+\/\=]/g;
            if (base64test.exec(input)) {
                window.alert("There were invalid base64 characters in the input text.\n" +
                    "Valid base64 characters are A-Z, a-z, 0-9, '+', '/',and '='\n" +
                    "Expect errors in decoding.");
            }
            input = input.replace(/[^A-Za-z0-9\+\/\=]/g, "");
  
            do {
                enc1 = keyStr.indexOf(input.charAt(i++));
                enc2 = keyStr.indexOf(input.charAt(i++));
                enc3 = keyStr.indexOf(input.charAt(i++));
                enc4 = keyStr.indexOf(input.charAt(i++));
  
                chr1 = (enc1 << 2) | (enc2 >> 4);
                chr2 = ((enc2 & 15) << 4) | (enc3 >> 2);
                chr3 = ((enc3 & 3) << 6) | enc4;
  
                output = output + String.fromCharCode(chr1);
  
                if (enc3 != 64) {
                    output = output + String.fromCharCode(chr2);
                }
                if (enc4 != 64) {
                    output = output + String.fromCharCode(chr3);
                }
  
                chr1 = chr2 = chr3 = "";
                enc1 = enc2 = enc3 = enc4 = "";
  
            } while (i < input.length);
  
            return output;
        }
    };
  
    /* jshint ignore:end */
});